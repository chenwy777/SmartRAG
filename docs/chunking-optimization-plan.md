# SmartRAG 分块策略优化计划

## 背景

当前 SmartRAG 的知识库入库链路是：

```text
上传完成
-> Kafka 消费
-> ParseService 解析文档
-> 规则分块并写入 document_vectors
-> VectorizationService 调用 Embedding
-> Elasticsearch knowledge_base 索引
-> HybridSearchService 做 KNN + BM25 检索
```

现有文本分块策略主要集中在 `ParseService`：

- 普通文档：Apache Tika 流式解析，先按父缓冲块处理，再切成子块。
- PDF：PDFBox 按页提取文本，清洗重复页眉页脚后按页分块。
- 子块默认大小：`file.parsing.chunk-size=512`。
- 切分顺序：段落 -> 句子 -> HanLP 分词 -> 字符兜底。
- 当前没有 overlap，也没有真实保存 parent chunk。

这套方案已经可用，但仍有几个明显优化空间：

- chunk 边界可能切断关键上下文。
- 过短 chunk 可能造成无意义召回。
- 只按字符数控制，不感知 token、章节、标题、表格等结构。
- 检索命中的是孤立 child chunk，给 LLM 的上下文可能不完整。
- 向量化读取 `document_vectors` 时缺少显式 `chunkId` 顺序约束。

## 最终目标

最终形态是结构化 Parent-Child RAG：

```text
文档解析
-> 结构识别：标题 / 段落 / 列表 / 表格 / 页码
-> Parent Chunk：保留完整上下文
-> Child Chunk：负责精准召回
-> Embedding child
-> Elasticsearch 混合检索
-> 命中 child 后扩展 parent / 邻近 chunk
-> rerank / 去重 / 控长
-> 给 LLM 生成回答
```

核心原则：

- 检索用小块，回答用大上下文。
- 结构优先，句子其次，HanLP 兜底。
- 引用定位使用 child，回答上下文使用 parent 或邻近窗口。

## 一期：增强现有分块策略

目标：不改数据库大结构，先把当前 `ParseService` 做稳。

### 改动范围

- `src/main/java/com/smartrag/service/ParseService.java`
- `src/main/java/com/smartrag/repository/DocumentVectorRepository.java`
- `src/main/java/com/smartrag/service/VectorizationService.java`
- `src/main/resources/application*.yml`
- `src/test/java/com/smartrag/service/*ParseService*Test.java`

### 任务

1. 新增配置项：

```yaml
file:
  parsing:
    chunk-size: 512
    overlap-size: 100
    min-chunk-size: 100
```

2. 增加 overlap。

- 优先按句子边界取 overlap。
- 句子过长时再按 HanLP 词边界取 overlap。
- 最后才按字符兜底。

3. 增加短块合并。

- 小于 `min-chunk-size` 的 chunk 尽量并入前一个或后一个 chunk。
- 避免标题、孤立短句、页尾残片单独入库。

4. 固定向量化读取顺序。

- 将 `findByFileMd5(...)` 调整为 `findByFileMd5OrderByChunkIdAsc(...)`。
- `VectorizationService` 使用显式顺序查询，避免依赖数据库默认返回顺序。

5. 保留 HanLP 但收窄定位。

- HanLP 只处理超长中文句子和 overlap 边界。
- 不把 HanLP 升级成主切分器。

6. 补充分块单测。

- 段落切分。
- 句子切分。
- 短块合并。
- overlap。
- 中文超长句 HanLP 兜底。
- 空文本和无标点文本。

### 验收标准

- `mvn -q -Dtest=ParseServiceUnitTest,ParseServiceTest test` 通过。
- 同一段长文本多次分块结果顺序稳定。
- chunk 不明显过短。
- 边界处能保留必要 overlap。
- 向量化读取顺序固定为 `chunkId ASC`。

## 二期：抽象 Chunker 模块

目标：把分块逻辑从 `ParseService` 中拆出来，降低后续演进成本。

### 建议结构

```text
src/main/java/com/smartrag/service/chunk/
  ChunkingProperties.java
  DocumentChunker.java
  ChunkCandidate.java
  ChunkResult.java
  ParagraphSplitter.java
  SentenceSplitter.java
  HanlpLongSentenceSplitter.java
  OverlapBuilder.java
```

### 任务

1. `ParseService` 只负责：

- 文档流解析。
- PDF 页文本提取和清洗。
- 调用 `DocumentChunker`。
- 保存 chunk。

2. `DocumentChunker` 负责：

- 段落切分。
- 句子切分。
- 超长句兜底。
- overlap。
- 短块合并。

3. 用 `@ConfigurationProperties` 绑定配置：

- `chunk-size`
- `overlap-size`
- `min-chunk-size`
- 后续可扩展 `parent-chunk-size`、`child-chunk-size`。

### 验收标准

- 分块逻辑可以脱离 Tika/PDFBox 独立单测。
- `ParseService` 复杂度下降。
- 当前入库、向量化、检索行为不被破坏。
- 一期所有测试继续通过。

## 三期：引入 Parent-Child 数据模型

目标：让“检索小块、回答大上下文”成为真实能力。

### 数据模型方案

短期可以兼容扩展 `document_vectors`：

```text
parent_chunk_id
chunk_type       -- PARENT / CHILD
chunk_index
section_title
section_path
start_offset
end_offset
token_count
```

长期可以迁移为独立表：

```text
document_chunks
  id
  file_md5
  parent_chunk_id
  chunk_type
  chunk_index
  text_content
  page_number
  anchor_text
  section_title
  section_path
  start_offset
  end_offset
  token_count
  user_id
  org_tag
  is_public
```

### 分块策略

- Parent chunk：1500-2500 字符，按章节、页、连续段落聚合。
- Child chunk：350-700 字符，带 overlap，用于 Embedding 和 ES 检索。
- 只对 child 做 Embedding。
- parent 存 MySQL，用于命中后的上下文扩展。

### 任务

1. 生成 parent chunk。
2. 在 parent 内生成 child chunk。
3. child 保存 `parentChunkId` 和 `chunkIndex`。
4. 删除、重建索引时同时清理 parent 和 child。
5. 向量化只读取 child。
6. ES 文档携带 parent 关联字段。

### 验收标准

- 一个文档能生成 parent + child。
- child 能稳定关联 parent。
- 原有文档列表、删除、重建索引不坏。
- 重建索引会清理旧 chunk 并重新生成 parent/child。
- ES 只索引 child，检索结果仍能返回准确引用。

## 四期：检索侧上下文扩展

目标：命中 child 后，不再只把孤立 child 交给 LLM。

### 改动范围

- `HybridSearchService`
- `ChatHandler`
- 引用映射逻辑
- ES 文档结构
- chunk 查询 repository

### 任务

1. ES 仍主要检索 child。
2. 命中 child 后按 `parentChunkId` 查询 parent。
3. 如果 parent 太大，按邻近窗口扩展：

```text
chunkIndex - 1
chunkIndex
chunkIndex + 1
```

4. 多个命中来自同一 parent 时做去重。
5. 对扩展上下文做长度控制。
6. 引用预览仍使用 child 的：

- `fileMd5`
- `chunkId`
- `pageNumber`
- `anchorText`

7. 可选加入 MMR，减少重复上下文。

### 验收标准

- 搜索结果引用定位仍准确。
- 回答上下文比单 chunk 更完整。
- prompt 不超长。
- 多个命中来自同一 parent 时不会重复塞入大段内容。
- 权限过滤仍按 `userId`、`orgTag`、`isPublic` 生效。

## 五期：结构化文档专项优化

目标：不同文档类型采用差异化切分策略。

### PDF

- 保留当前页眉页脚清洗。
- 继续保存 `pageNumber`。
- 短页可以跨页合并成 parent，但 child 引用仍指向原始页码。

### Markdown

- 按标题层级生成 `sectionPath`。
- 标题和正文绑定，不让标题单独成为孤立 chunk。
- 代码块可以作为独立结构保留。

### Word

- 尽量识别标题、段落、列表。
- 标题层级写入 `sectionTitle` / `sectionPath`。

### 表格

- 按“表头 + 若干行”切块。
- 表头在每个表格 child 中重复保留。
- 不按普通句子逻辑拆表格。

### OCR / 无标点文本

- HanLP 作为中文词边界兜底。
- 必要时按固定长度加 overlap。

### 验收标准

- Markdown 标题不会单独成为孤立 chunk。
- 表格不被拆成无意义短句。
- PDF 引用能定位到页。
- OCR 或无标点文本不会生成超长 chunk。

## HanLP 定位

HanLP 保留，但定位不是主分块策略。

推荐定位：

- 超长中文句子分词兜底。
- overlap 边界保护。
- 可选提取关键词，写入 ES 的 `keywords` 字段，增强 BM25。

不推荐：

- 不要一开始就对整篇文档 HanLP 分词。
- 不要用 HanLP 替代文档结构识别。
- 不要把标题、表格、列表关系打散后再分词。

## 推荐推进顺序

1. 一期：增强现有分块策略，低风险高收益。
2. 二期：抽象 Chunker，为 Parent-Child 铺路。
3. 三期：引入 Parent-Child 数据模型。
4. 四期：检索侧上下文扩展。
5. 五期：结构化文档专项优化。

实际开发建议先从一期开始，不动数据库结构，先把分块质量、顺序稳定性和测试补上。

