## 1. WebSocket 流式对话与主动中断

### 简历描述
> 基于 WebSocket 实现全双工流式对话，后端通过 WebFlux 的 Stream 接入 DeepSeek 等 LLM 的 SSE 流，实现逐字输出；支持用户主动中断，后端同步取消 LLM 侧的流式连接，避免错误提示词下的 Token 消耗；整体首字响应延迟控制在 500ms 以内。

### 面试提问

#### Q1: 为什么选择 WebSocket 而不是 SSE 来实现流式对话？

考察点：WebSocket vs SSE 的技术选型

参考答案：SSE 是单向通道（服务端→客户端），无法在流式输出过程中接收客户端的中断指令；WebSocket 是全双工通信，客户端可以在接收流式输出的同时发送 stop 命令。此外 WebSocket 支持心跳保活（ping/pong），更适合长连接场景。

#### Q2: 用户点击"停止生成"后，后端做了哪些事情确保 LLM 也停下来？

考察点：流式中断的完整链路

参考答案：分三步

(1) 在 `stopFlags` 中标记该 generationId 为已取消；

(2) 从 `activeStreams` 中取出 `StreamHandle`，调用 `cancel()` 方法，内部调用 Reactor `Disposable.dispose()` 断开与 LLM API 的 HTTP 流连接；

(3) 对关联的 `CompletableFuture` 调用 `completeExceptionally(CancellationException)` 唤醒完成监控线程，向客户端推送 `{type:"stop"}`。

源码位置：`ChatHandler.java:520-558`

关键代码：

```java
public void stopResponse(String userId, String generationId) {
    cancelledGenerations.add(generationId);
    stopFlags.put(generationId, true);
    StreamHandle handle = activeStreams.remove(generationId);
    if (handle != null) {
        handle.cancel(); // Disposable.dispose() → 断开 HTTP 流
    }
    CompletableFuture<String> future = responseFutures.remove(generationId);
    if (future != null) {
        future.completeExceptionally(new CancellationException("User stopped"));
    }
}
```

#### Q3: stop 指令中为什么要加一个 token 校验？去掉会有什么风险？

考察点：安全性设计

参考答案：`WSS_STOP_CMD_` + 时间戳片段构成的 token 用于验证 stop 指令来自合法客户端，而非通过 XSS 或 CSRF 注入的恶意消息。去掉后攻击者可以构造 `{type:"stop"}` 消息中断其他用户的对话。

源码位置：`ChatWebSocketHandler.java:28, 99-113`

### 延伸问题
- 后端用了 5 个 `ConcurrentHashMap` 存储流式状态，如果服务重启全部丢失怎么办？
- 如果用户快速连续发送多条消息，如何防止并发冲突？

---

## 2. 对话内容持久化与断线续传

### 简历描述
> 实现对话内容的持久化和断线续传，每个流式内容原子写入 Redis，并通过状态机维持状态；用户断线重连后自动恢复上一次输出进度，不丢失已生成文本，保障弱网环境下的用户体验。

### 面试提问

#### Q1: 为什么用 Redis 的 `append()` 而不是 `set()` 来写入流式内容？

考察点：Redis 数据操作与并发安全

参考答案：`set()` 是覆盖写，需要先读再拼接，存在读-改-写的竞态条件。`append()` 是原子操作，直接在现有字符串末尾追加，无需加锁，天然适合流式场景中高频小片段的增量写入。

源码位置：`ChatGenerationStateService.java:51`

#### Q2: 用户断线重连后，怎么判断上一次输出是"还在进行中"还是"已结束"？

考察点：状态机设计

参考答案：Redis 中存储了 `GenerationMeta`，包含 status 字段（STREAMING / COMPLETED / FAILED / CANCELLED）。重连时客户端带上 `generationId`，后端查询 status：STREAMING 则返回已累积内容并继续推送；COMPLETED/CANCELLED 则一次性返回完整内容。

源码位置：`ChatGenerationStateService.java:235-240`

#### Q3: 断线重连时，如何避免旧连接的注销回调误删新连接？

考察点：并发安全设计

源码位置：`ChatSessionRegistry.java:29`

关键代码：
```java
public void unregisterSession(String userId, WebSocketSession session) {
    sessions.computeIfPresent(userId, (k, existing) ->
        existing == session ? null : existing
    );
}
```

参考答案：用 `computeIfPresent` 做引用相等检查——只有当 Map 中存的 session 对象和要注销的是同一个时才移除。这样即使旧连接的 `afterConnectionClosed` 回调晚于新连接的注册，也不会误删新 session。

### 延伸问题
- Redis 中生成内容的 TTL 是 30 分钟，过期后用户回来怎么办？
- 如果 Redis 宕机，流式对话还能用吗？（答：WebSocket 直推不受影响，只是断线续传能力降级）

---

## 3. ES 混合检索与向量化

### 简历描述
> 利用 Elasticsearch + IK 分词器对知识库文档进行索引和向量检索，支持 Word、PDF 和 TXT 等多种文本类型；并集成阿里 Embedding 模型进行文本到向量的转换，支持 2048 维；再结合 ES 的 KNN 向量召回、关键词过滤和 BM25 重排序实现「关键词+语义」的双引擎搜索，同时内嵌多租户权限过滤，确保数据隔离。

### 面试提问

#### Q1: 为什么不直接用纯向量检索，而要做 KNN + BM25 混合？

考察点：RAG 检索策略选型

参考答案：纯 KNN 依赖语义相似度，对专业术语、产品名称等精确匹配敏感度低（"iPhone 16"和"iPhone 15"向量距离可能很近）；纯 BM25 无法理解同义词和语义改写。混合检索先用 KNN 做语义召回保证覆盖率，再用 BM25 做精排保证精确度，两者互补。

#### Q2: KNN 召回阶段的 numCandidates 设了 topK 的 30 倍，这个系数怎么定的？

考察点：向量检索参数调优

参考答案：`numCandidates` 是 HNSW 近似搜索的候选集大小。太小（如 2x）召回率不足，太大（如 100x）接近暴力搜索延迟高。30x 是经验值，兼顾召回率和延迟，实际可通过离线 Recall@K 评测调优。

源码位置：`HybridSearchService.java:86-95`

#### Q3: rescore 阶段 queryWeight:0.2 和 rescoreQueryWeight:1.0 是怎么配合的？

考察点：ES rescore 机制

源码位置：`HybridSearchService.java:121-132`

关键代码：
```java
s.rescore(r -> r
    .query(rq -> rq
        .rescoreQuery(q -> q.match(m -> m
            .field("textContent")
            .query(query)
            .operator(Operator.And)))
        .queryWeight(0.2)
        .rescoreQueryWeight(1.0)
    )
    .windowSize(topK * 2)
);
```

参考答案：最终得分 = 0.2 × KNN_score + 1.0 × BM25_score。BM25 权重是 KNN 的 5 倍，意味着关键词匹配度对排名影响更大。这种设计适合企业知识库——用户查询通常包含明确关键词，语义理解起辅助召回作用。

### 延伸问题
- 多租户权限过滤为什么放在 KNN 阶段的 filter 里，而不是 rescore 之后？
- 当 Embedding API 不可用时如何降级？（答：走纯 BM25 的 `textOnlySearchWithPermission()` 路径）

---

## 4. 大文件分片并发上传

### 简历描述
> 前端使用 SparkMD5 计算文件指纹，然后开启 4 个 worker 并发上传分片；后端通过 Bitmap 追踪分片状态；合并阶段按 chunkIndex 升序组装后调用 MinIO 进行合并，保证分片不乱序。

### 面试提问

#### Q1: 4 个 worker 并发上传，分片到达顺序不确定，最终合并时怎么保证不乱序？

考察点：并发上传的有序性保证

参考答案：每个分片带自己的 `chunkIndex` 独立存储到 MinIO（路径为 `chunks/{fileMd5}/{chunkIndex}`），上传顺序无所谓。合并时后端通过 `findByFileMd5OrderByChunkIndexAsc` 按索引升序查出所有分片路径，再按此顺序传给 `composeObject()`，所以最终文件一定是正确顺序。

源码位置：`UploadService.java:534, 546-548`

#### Q2: 为什么用 Bitmap 而不是 Set 或 Hash 来追踪分片状态？

考察点：数据结构选型

参考答案：Bitmap 空间效率极高——1000 个分片只需 125 字节（1000/8），而 Set 要存 1000 个元素；`setBit/getBit` 都是 O(1)；判断"是否全部上传完成"只需一次 `GET` 拿完整位图逐位检查，不需要额外的 `SCARD` 调用。

源码位置：`UploadService.java:329-370`

#### Q3: 前端的并发上传模型是怎么实现的？为什么不直接用 Promise.all？

考察点：前端并发控制

源码位置：`knowledge-base/index.ts:58-82`

关键代码：
```typescript
async function uploadChunksInParallel(task, chunkIndexes: number[]) {
  const workerCount = Math.min(maxConcurrentChunksPerFile, chunkIndexes.length);
  const runWorker = async (): Promise<void> => {
    const chunkIndex = chunkIndexes.shift();
    if (chunkIndex === undefined) return;
    const success = await uploadChunk(task, chunkIndex);
    if (!success) { uploadError = new Error(...); return; }
    await runWorker(); // 递归取下一个
  };
  await Promise.all(Array.from({ length: workerCount }, () => runWorker()));
}
```

参考答案：如果直接 `Promise.all(所有分片)` 会同时发起几百个请求，造成浏览器连接池满和服务端压力。这里用的是 worker pool 模式——固定 4 个 worker，每个 worker 完成一个分片后从队列 `shift()` 取下一个，递归执行直到队列为空。既保证了并发，又控制了同时在线的请求数。

### 延伸问题
- 合并前如果某个分片在 MinIO 上丢了怎么办？（答：合并前逐一 `statObject` 检查）
- SparkMD5 计算文件指纹用来做什么？（答：秒传判断 + 断点续传的分片关联）
- Redis Bitmap 读取时为什么要做 `7 - (bitIndex % 8)` 的位翻转？

---

## 5. Kafka 异步文档处理管线

### 简历描述
> 利用 Kafka 进行异步文档处理，覆盖「文件解析 → 文本切片 → 向量化入库」全流程，并通过事务确保生产者侧消息不丢失不重复，消费端失败的消息经 4 次重试后自动路由至死信队列。

### 面试提问

#### Q1: Kafka 事务生产者解决了什么问题？和幂等生产者有什么区别？

考察点：Kafka 可靠性机制

参考答案：幂等生产者（`enable.idempotence=true`）通过 PID + sequence number 解决重试导致的消息重复；事务生产者在此基础上保证跨分区的原子写入——要么全部提交要么全部回滚，配合数据库事务可实现端到端 Exactly-Once。

源码位置：`KafkaConfig.java:84-90`

#### Q2: 为什么重试策略选了固定间隔 3 秒而不是指数退避？

考察点：重试策略设计权衡

参考答案：文件处理的失败原因通常是确定的（文件损坏、格式不支持、外部 API 不可达），指数退避只是延长等待但不改变结果。固定 3 秒在 12 秒内完成 4 次重试，快速确认是否为暂时性故障；持续失败的消息立即进 DLT 不阻塞后续消费。

源码位置：`KafkaConfig.java:117-122`

#### Q3: 消费端的状态机（PENDING → PROCESSING → COMPLETED/FAILED）为什么重要？

考察点：分布式系统可靠性

源码位置：`FileProcessingConsumer.java:42-98`

关键代码：
```java
documentService.markVectorizationProcessing(fileId);
try {
    parseService.parseAndSave(file, inputStream);
    vectorizationService.vectorizeWithUsage(fileMd5, ...);
    documentService.markVectorizationCompleted(fileId, usage);
} catch (Exception e) {
    documentService.markVectorizationFailed(fileId, e.getMessage());
    throw new RuntimeException(e); // 触发 Kafka 重试
}
```

参考答案：三个作用——

(1) 前端可以实时展示 PENDING/PROCESSING/COMPLETED/FAILED 进度；

(2) Consumer 崩溃后 rebalance，新 Consumer 看到 PROCESSING 状态可以决定是否清理半成品数据；

(3) FAILED 记录错误信息，支持排查和手动重试。

### 延伸问题
- 如果向量化 API 限流了，如何做到解析成功但向量化延迟重试？
- 死信队列里的消息后续怎么处理？

---

## 6. Redis + MySQL 双层对话存储

### 简历描述
> 通过 Redis 维护 20 条消息的上下文窗口，供 LLM 调用时毫秒级加载；并将完整对话内容持久化到 MySQL。

### 面试提问

#### Q1: 为什么 LLM 调用时只用 Redis 中的 20 条消息，而不从 MySQL 加载全部历史？

考察点：LLM Context Window 管理

参考答案：三个原因——LLM 有 context window 限制，过多历史会超出 token 上限或增加成本；MySQL 查询 + 反序列化延迟远高于 Redis 内存读取；20 条（约 10 轮对话）已足够保持上下文连贯，更早的对话贡献递减。

#### Q2: 对话的引用来源映射为什么存 MySQL 而不是 Redis？

考察点：数据分层设计

参考答案：引用映射（文件名、页码、匹配片段等）是审计型数据，需要长期保存用于溯源和合规；Redis 有 TTL 不适合做持久化。而且引用映射只在查看历史对话时才需要，不在实时对话的路径上。

源码位置：`ConversationService.java:53-71`

#### Q3: 如果 Redis 数据丢了（重启或驱逐），对话会"失忆"吗？

考察点：数据一致性

参考答案：不会。Redis 是 MySQL 的子集，数据丢失后下一次对话请求会从 MySQL 重新加载最近历史重建 Redis 上下文。这是经典的"Redis 作为缓存，MySQL 作为数据源"模式，不需要双写一致性保证。

### 延伸问题
- 每次对话都写 MySQL 会不会成为性能瓶颈？如何优化？
- 7 天 TTL 到期后 Redis 上下文清空，对新一轮对话有影响吗？

---

## 7. 热插拔模型路由与 Token 配额

### 简历描述
> 实现可热插拔的多模型路由与 Token 配额管理，支持运行时切换 DeepSeek、通义千问等 LLM 和 Embedding 供应商，无需重启服务；并通过流式 usage 解析实现精确的 Token 消耗计量，结合每日请求数 + Token 余额双维度限流，控制调用成本。

### 面试提问

#### Q1: "热插拔"是怎么实现的？切换模型需要重启服务吗？

考察点：运行时配置管理

参考答案：不需要重启。每次 LLM/Embedding 调用时从数据库查询当前激活的 provider 配置（API URL、Key、模型名），而不是启动时读取一次。管理员在后台切换后，下一次调用自动使用新配置。

源码位置：`LlmProviderRouter.java:42-82`, `EmbeddingClient.java:122-145`

#### Q2: 流式输出中怎么精确统计 Token 消耗？API 不返回 usage 怎么办？

考察点：LLM Token 计量

参考答案：优先从 SSE 流的最后一帧解析 `usage.prompt_tokens` 和 `usage.completion_tokens`。如果 API 没有返回 usage 帧，降级使用基于字符数的估算（中文/英文不同比例）。`volatile int` 字段保证多线程可见性。

源码位置：`LlmProviderRouter.java:144-196, 215-227`

#### Q3: 每日请求数 + Token 余额双维度限流是怎么配合的？

考察点：限流策略设计

参考答案：两个维度独立检查，任一触发都拒绝——每日请求数用 Redis 原子递增 + 当日过期 key，防止单用户高频调用；Token 余额在发起调用前预估消耗量预扣，流结束后按实际消耗结算差额。预扣-结算模式既防超额，又不因估算偏差影响体验。

### 延伸问题
- 预扣后 LLM 调用失败了，预扣的 Token 怎么退还？
- 多个模型 Token 定价不同，如何统一计量？