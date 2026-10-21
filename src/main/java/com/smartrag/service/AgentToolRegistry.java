package com.smartrag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.DocStats;
import co.elastic.clients.elasticsearch._types.StoreStats;
import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import co.elastic.clients.elasticsearch.indices.stats.IndicesStats;
import com.smartrag.client.DeepSeekClient;
import com.smartrag.entity.SearchResult;
import com.smartrag.model.FileUpload;
import com.smartrag.repository.FileUploadRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class AgentToolRegistry {

    private static final String KNOWLEDGE_INDEX = "knowledge_base";
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_SEARCH_DOCS = 20;

    private final HybridSearchService hybridSearchService;
    private final DeepSeekClient deepSeekClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ElasticsearchClient elasticsearchClient;
    private final FileUploadRepository fileUploadRepository;
    private final List<AgentTool> tools;
    private final Map<String, ToolHandler> handlers;

    public AgentToolRegistry(HybridSearchService hybridSearchService,
                             DeepSeekClient deepSeekClient,
                             StringRedisTemplate stringRedisTemplate,
                             ElasticsearchClient elasticsearchClient,
                             FileUploadRepository fileUploadRepository) {
        this.hybridSearchService = hybridSearchService;
        this.deepSeekClient = deepSeekClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.elasticsearchClient = elasticsearchClient;
        this.fileUploadRepository = fileUploadRepository;
        this.tools = List.of(
                searchKnowledgeTool(),
                generateSummaryTool(),
                submitFeedbackTool(),
                knowledgeStatsTool()
        );
        this.handlers = Map.of(
                "search_knowledge", this::executeSearchKnowledge,
                "generate_summary", this::executeGenerateSummary,
                "submit_feedback", this::executeSubmitFeedback,
                "knowledge_stats", this::executeKnowledgeStats
        );
    }

    public List<AgentTool> getTools() {
        return tools;
    }

    public Optional<AgentTool> getTool(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    public ToolExecutionResult executeTool(String name, Map<String, Object> arguments, String userId) {
        return executeTool(name, arguments, userId, null);
    }

    public ToolExecutionResult executeTool(String name,
                                           Map<String, Object> arguments,
                                           String userId,
                                           Consumer<String> onChunk) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return handler.execute(arguments == null ? Collections.emptyMap() : arguments, userId, onChunk);
    }

    private ToolExecutionResult executeSearchKnowledge(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk) {
        requireUserId(userId);
        String query = getRequiredString(arguments, "query");
        int topK = getInt(arguments, "topK", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);

        List<SearchResult> results = hybridSearchService.searchWithPermission(query, userId, topK);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("topK", topK);
        data.put("results", results);
        return new ToolExecutionResult("search_knowledge", true, formatSearchResults(results), data);
    }

    private ToolExecutionResult executeGenerateSummary(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk) {
        requireUserId(userId);
        String topic = getRequiredString(arguments, "topic");
        int maxDocs = getInt(arguments, "maxDocs", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);

        List<SearchResult> results = hybridSearchService.searchWithPermission(topic, userId, maxDocs);
        String summary = deepSeekClient.summarize(userId, topic, results, onChunk);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("maxDocs", maxDocs);
        data.put("sourceCount", results.size());
        data.put("sources", results);

        String content = "主题：" + topic + "\n"
                + "检索片段数：" + results.size() + "\n\n"
                + summary;
        return new ToolExecutionResult("generate_summary", true, content, data, onChunk != null);
    }

    private ToolExecutionResult executeSubmitFeedback(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        requireUserId(userId);
        String rating = getRequiredString(arguments, "rating").toLowerCase(Locale.ROOT);
        if (!"good".equals(rating) && !"bad".equals(rating)) {
            throw new IllegalArgumentException("rating 只允许 good 或 bad");
        }
        String reason = getOptionalString(arguments, "reason");
        String key = "feedback:" + userId;
        String field = String.valueOf(System.currentTimeMillis());
        String value = reason == null || reason.isBlank()
                ? "rating=" + rating
                : "rating=" + rating + "; reason=" + reason;
        stringRedisTemplate.opsForHash().put(key, field, value);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("field", field);
        data.put("rating", rating);
        data.put("reason", reason);
        return new ToolExecutionResult("submit_feedback", true, "已记录用户反馈: " + value, data);
    }

    private ToolExecutionResult executeKnowledgeStats(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        try {
            IndicesStatsResponse statsResponse = elasticsearchClient.indices().stats(s -> s.index(KNOWLEDGE_INDEX));
            IndicesStats indexStats = statsResponse.indices().get(KNOWLEDGE_INDEX);
            DocStats docStats = indexStats != null && indexStats.total() != null ? indexStats.total().docs() : null;
            StoreStats storeStats = indexStats != null && indexStats.total() != null ? indexStats.total().store() : null;

            long documentCount = fileUploadRepository.count();
            long fragmentCount = docStats != null ? docStats.count() : 0L;
            Long deletedFragmentCount = docStats != null ? docStats.deleted() : null;
            Long storeSizeInBytes = storeStats != null ? storeStats.sizeInBytes() : null;
            LocalDateTime latestUpdatedAt = fileUploadRepository.findFirstByOrderByMergedAtDesc()
                    .map(this::resolveLatestUpdatedAt)
                    .orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", KNOWLEDGE_INDEX);
            data.put("documentCount", documentCount);
            data.put("fragmentCount", fragmentCount);
            data.put("deletedFragmentCount", deletedFragmentCount);
            data.put("storeSizeInBytes", storeSizeInBytes);
            data.put("latestUpdatedAt", latestUpdatedAt);

            return new ToolExecutionResult("knowledge_stats", true, formatKnowledgeStats(data), data);
        } catch (Exception e) {
            throw new RuntimeException("获取知识库统计信息失败", e);
        }
    }

    private AgentTool searchKnowledgeTool() {
        return new AgentTool(
                "search_knowledge",
                "在知识库中搜索与用户问题相关的文档片段。当用户问题的答案可能依赖已上传资料、企业/项目/产品/系统内部信息、专有名词、事实依据、定义、功能、使用方式、实现细节、背景、流程或引用来源时应调用；即使用户没有明确说“查询知识库”，只要问题不像纯通用常识也应先检索。普通问候、闲聊、纯创作、翻译、通用代码/常识问题，或用户明确要求不要查知识库时不要调用。",
                objectSchema(Map.of(
                        "query", stringSchema("用于知识库检索的查询语句。应保留用户原话中的核心实体、缩写和限定词，可包含原始问句和必要的等价改写；不要替换成固定关键词。"),
                        "topK", integerSchema("返回的片段数量，默认 5。")
                ), List.of("query"))
        );
    }

    private AgentTool generateSummaryTool() {
        return new AgentTool(
                "generate_summary",
                "对指定主题的知识库文档生成结构化摘要。适合用户要求整理、总结、归纳、提炼知识库内容时调用；本工具内部会二次调用大模型完成摘要，外层 ReAct 循环只应接收结果，不要把内部摘要过程当作新的工具计划。",
                objectSchema(Map.of(
                        "topic", stringSchema("需要从知识库中整理和总结的主题。"),
                        "maxDocs", integerSchema("用于生成摘要的最多相关片段数量，默认 5。")
                ), List.of("topic"))
        );
    }

    private AgentTool submitFeedbackTool() {
        Map<String, Object> ratingSchema = stringSchema("用户对当前回答的评价，只能是 good 或 bad。");
        ratingSchema.put("enum", List.of("good", "bad"));
        return new AgentTool(
                "submit_feedback",
                "当用户明确表达对回答满意、不满意、点赞、点踩、纠错或要求记录反馈时调用，用于记录反馈以优化后续回答质量；不要在没有明确评价意图时推断调用。",
                objectSchema(Map.of(
                        "rating", ratingSchema,
                        "reason", stringSchema("用户给出的满意或不满意原因，可为空。")
                ), List.of("rating"))
        );
    }

    private AgentTool knowledgeStatsTool() {
        return new AgentTool(
                "knowledge_stats",
                "返回当前知识库的统计信息，包括 MySQL 文档总数、Elasticsearch 片段总数、索引存储量和最近更新时间。仅当用户询问知识库规模、文档数量、片段数量、更新时间或索引状态时调用。",
                objectSchema(Collections.emptyMap(), Collections.emptyList())
        );
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> stringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> integerSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private String formatSearchResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "未检索到相关知识库片段。";
        }

        StringBuilder output = new StringBuilder("检索到 ").append(results.size()).append(" 个知识库片段。")
                .append("请基于这些片段回答用户问题；不得声称知识库暂无相关信息。")
                .append("如果片段信息不足，请说明“基于已检索片段只能确认……”并标注来源编号。");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            output.append("\n\n[").append(i + 1).append("] ");
            if (result.getFileName() != null && !result.getFileName().isBlank()) {
                output.append(result.getFileName()).append(" ");
            }
            output.append("(fileMd5=").append(result.getFileMd5())
                    .append(", chunkId=").append(result.getChunkId());
            if (result.getPageNumber() != null) {
                output.append(", page=").append(result.getPageNumber());
            }
            if (result.getScore() != null) {
                output.append(", score=").append(String.format(Locale.ROOT, "%.4f", result.getScore()));
            }
            output.append(")\n")
                    .append(limitText(result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(), 1200));
        }
        return output.toString();
    }

    private String formatKnowledgeStats(Map<String, Object> data) {
        return "知识库统计："
                + "\n- MySQL 文档总数：" + data.get("documentCount")
                + "\n- Elasticsearch 片段总数：" + data.get("fragmentCount")
                + "\n- ES 已删除片段数：" + nullToDash(data.get("deletedFragmentCount"))
                + "\n- ES 存储大小(bytes)：" + nullToDash(data.get("storeSizeInBytes"))
                + "\n- 最近更新时间：" + nullToDash(data.get("latestUpdatedAt"));
    }

    private LocalDateTime resolveLatestUpdatedAt(FileUpload fileUpload) {
        if (fileUpload.getMergedAt() != null) {
            return fileUpload.getMergedAt();
        }
        return fileUpload.getCreatedAt();
    }

    private String getRequiredString(Map<String, Object> arguments, String name) {
        String value = getOptionalString(arguments, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private String getOptionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private int getInt(Map<String, Object> arguments, String name, int defaultValue, int min, int max) {
        Object raw = arguments.get(name);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }

        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            value = Integer.parseInt(String.valueOf(raw));
        }
        return Math.max(min, Math.min(max, value));
    }

    private String limitText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少 userId，无法执行带权限的知识库或反馈操作");
        }
    }

    @FunctionalInterface
    private interface ToolHandler {
        ToolExecutionResult execute(Map<String, Object> arguments, String userId, Consumer<String> onChunk);
    }

    public record AgentTool(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
    }

    public record ToolExecutionResult(
            String toolName,
            boolean success,
            String content,
            Map<String, Object> data,
            boolean streamedToUser
    ) {
        public ToolExecutionResult(String toolName,
                                   boolean success,
                                   String content,
                                   Map<String, Object> data) {
            this(toolName, success, content, data, false);
        }
    }
}
