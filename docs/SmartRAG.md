# SmartRAG 技术概览

SmartRAG 是基于检索增强生成（RAG）的 AI 知识库管理系统，支持文档上传、解析、向量化、混合检索和实时问答。

## 文档处理

文档通过分片上传进入 MinIO，Kafka 消费者异步处理文件。解析层结合 Apache Tika、LiteParse 和可配置的 OCR 服务提取文本，再进行语义分块和向量化，并写入 Elasticsearch。

## 检索与问答

系统结合关键词与向量检索获取相关片段，将检索结果作为上下文交给配置的大语言模型，通过 WebSocket 返回回答。聊天记录持久化到 MySQL，Redis 用于缓存和短期上下文。

## 权限管理

Spring Security 和 JWT 负责身份认证。组织标签、用户角色以及文档公开性共同控制知识库访问范围，支持多租户使用。

## 技术组成

- 后端：Java 17、Spring Boot、Spring Data JPA、Spring Security。
- 前端：Vue 3、TypeScript、Vite、Naive UI、Pinia。
- 数据与基础设施：MySQL、Redis、Elasticsearch、Kafka、MinIO。
- AI 服务：可配置的大语言模型与 Embedding 提供方。

环境准备、配置文件和启动步骤见 [README](../README.md)。
