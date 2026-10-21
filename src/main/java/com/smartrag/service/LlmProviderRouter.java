package com.smartrag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrag.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class LlmProviderRouter {

    private static final Logger logger = LoggerFactory.getLogger(LlmProviderRouter.class);
    private static final int REACT_HISTORY_MAX_MESSAGES = 6;
    private static final int REACT_HISTORY_MAX_CONTENT_CHARS = 800;
    private static final int DEFAULT_REACT_MAX_COMPLETION_TOKENS = 2000;

    private final AiProperties aiProperties;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;
    private final ObjectMapper objectMapper;

    public LlmProviderRouter(AiProperties aiProperties,
                             RateLimitService rateLimitService,
                             UsageQuotaService usageQuotaService,
                             ModelProviderConfigService modelProviderConfigService,
                             ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
        this.objectMapper = objectMapper;
    }

    public StreamHandle streamResponse(String requesterId,
                                       String userMessage,
                                       String context,
                                       List<Map<String, String>> history,
                                       Consumer<String> onChunk,
                                       Consumer<Throwable> onError,
                                       Consumer<StreamCompletion> onComplete) {

        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        Map<String, Object> request = buildRequest(provider.model(), userMessage, context, history);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
        int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 2000;
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);

        try {
            Disposable subscription = buildClient(provider)
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            chunk -> processChunk(chunk, usageTracker, onChunk),
                            error -> {
                                settleUsage(usageTracker);
                                onError.accept(error);
                            },
                            () -> {
                                settleUsage(usageTracker);
                                logger.info("LLM 流式响应完成: provider={}, model={}, finishReason={}, promptTokens={}, completionTokens={}, responseChars={}",
                                        provider.provider(),
                                        provider.model(),
                                        usageTracker.finishReason == null ? "unknown" : usageTracker.finishReason,
                                        usageTracker.promptTokens,
                                        usageTracker.completionTokens,
                                        usageTracker.responseContent.length());
                                if (onComplete != null) {
                                    onComplete.accept(new StreamCompletion(
                                            usageTracker.finishReason,
                                            usageTracker.promptTokens,
                                            usageTracker.completionTokens,
                                            usageTracker.responseContent.length()
                                    ));
                                }
                            }
                    );
            return new StreamHandle(subscription, () -> settleUsage(usageTracker));
        } catch (Exception exception) {
            usageQuotaService.abortReservation(reservation);
            throw exception;
        }
    }

    public List<Map<String, Object>> buildReActMessages(String userMessage,
                                                        String context,
                                                        List<Map<String, String>> history) {
        return buildReActMessages(userMessage, context, history, "");
    }

    public List<Map<String, Object>> buildReActMessages(String userMessage,
                                                        String context,
                                                        List<Map<String, String>> history,
                                                        String feedbackGuidance) {
        List<Map<String, Object>> messages = new ArrayList<>();
        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        StringBuilder sysBuilder = new StringBuilder();
        if (promptCfg.getRules() != null) {
            sysBuilder.append(promptCfg.getRules()).append("\n\n");
        }
        sysBuilder.append("本系统是「知识库优先」的问答助手：你的首要职责是基于本系统已收录的资料回答用户。除非命中下方明确的白名单，否则**每一个用户问题都必须先调用 search_knowledge**，再基于检索结果作答。\n\n")
                .append("强制检索原则（默认行为）：\n")
                .append("1. 默认调用 search_knowledge：只要问题涉及任何实体、名称、缩写、产品、项目、术语、流程、功能、实现、背景、对比、引用，或包含「这/它/该/上述/这个/那个」等上下文指代，无论你是否自认为已知答案，都必须先检索，不要等用户说「查知识库」。\n")
                .append("2. 构造 query 时严格保留用户原话中的核心名词、缩写和限定词，禁止替换为泛化关键词；必要时可在同一次 query 中合并原句与等价改写。\n")
                .append("3. 用户要求整理、总结、归纳、提炼知识库内容时，先用 search_knowledge 圈定材料，再调用 generate_summary 生成总结。\n\n")
                .append("可以跳过 search_knowledge 的白名单（必须严格匹配其一，否则一律检索）：\n")
                .append("- 纯打招呼或寒暄（你好/谢谢/再见等）；\n")
                .append("- 纯翻译请求（把 X 翻译为 Y），且不涉及本系统术语；\n")
                .append("- 与本系统材料无关的纯创作请求（写诗、写段子等）；\n")
                .append("- 通用编程语法、数学计算等完全不依赖任何专有信息的常识题；\n")
                .append("- 用户在本轮明确表示「不要查知识库 / 直接回答」。\n\n")
                .append("回答与异常处理：\n")
                .append("- 只要 search_knowledge 返回了片段，必须基于片段作答并按来源编号标注，禁止回答「知识库暂无相关信息」。\n")
                .append("- 只有工具明确返回零片段时，才说明暂无相关材料并提示用户补充线索。\n")
                .append("- 工具失败时根据错误信息决定下一步（重试 / 换 query / 继续推理），不要直接中断。\n")
                .append("- 如需记录反馈或查看知识库统计，通过 tool_calls 调用对应工具。\n")
                .append("拿到 tool 结果后继续推理并给出最终回答。\n\n");
        if (feedbackGuidance != null && !feedbackGuidance.isBlank()) {
            sysBuilder.append(feedbackGuidance.trim()).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");
        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            sysBuilder.append(promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无预置检索结果，可按需调用工具）").append("\n");
        }
        sysBuilder.append(refEnd);

        messages.add(newMessage("system", sysBuilder.toString()));
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - REACT_HISTORY_MAX_MESSAGES);
            for (Map<String, String> message : history.subList(start, history.size())) {
                String role = message.get("role");
                String content = message.get("content");
                if (role == null || role.isBlank() || content == null || content.isBlank()) {
                    continue;
                }
                if ("user".equals(role) || "assistant".equals(role) || "system".equals(role)) {
                    messages.add(newMessage(role, limitText(content, REACT_HISTORY_MAX_CONTENT_CHARS)));
                }
            }
        }
        messages.add(newMessage("user", userMessage));
        return messages;
    }

    public ReActTurn completeReActTurn(String requesterId,
                                       List<Map<String, Object>> messages,
                                       List<AgentToolRegistry.AgentTool> tools,
                                       int maxCompletionTokens) {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        Map<String, Object> request = buildReActRequest(provider.model(), messages, tools, maxCompletionTokens, false);

        int estimatedPromptTokens = estimateObjectMessagesTokens(messages)
                + (tools == null || tools.isEmpty() ? 0 : estimateToolsTokens(tools));
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, maxCompletionTokens);

        try {
            String responseBody = buildClient(provider)
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(90));
            ReActTurn turn = parseReActTurn(responseBody, estimatedPromptTokens);
            usageQuotaService.settleReservation(reservation, turn.promptTokens() + turn.completionTokens());
            logger.info("ReAct 回合完成: provider={}, model={}, finishReason={}, toolCalls={}, contentChars={}",
                    provider.provider(), provider.model(), turn.finishReason(), turn.toolCalls().size(), turn.content().length());
            return turn;
        } catch (Exception exception) {
            usageQuotaService.abortReservation(reservation);
            throw new RuntimeException("ReAct 模型回合调用失败", exception);
        }
    }

    public StreamHandle streamReActTurn(String requesterId,
                                        List<Map<String, Object>> messages,
                                        List<AgentToolRegistry.AgentTool> tools,
                                        int maxCompletionTokens,
                                        Consumer<String> onChunk,
                                        Consumer<Throwable> onError,
                                        Consumer<ReActTurn> onComplete) {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        Map<String, Object> request = buildReActRequest(provider.model(), messages, tools, maxCompletionTokens, true);
        int estimatedPromptTokens = estimateObjectMessagesTokens(messages)
                + (tools == null || tools.isEmpty() ? 0 : estimateToolsTokens(tools));
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, Math.max(maxCompletionTokens, 1));
        ReActStreamAccumulator accumulator = new ReActStreamAccumulator(reservation, estimatedPromptTokens);

        try {
            Disposable subscription = buildClient(provider)
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            chunk -> processReActStreamChunk(chunk, accumulator, onChunk),
                            error -> {
                                logProviderError("ReAct 流式回合调用失败", error);
                                settleReActStreamUsage(accumulator);
                                onError.accept(error);
                            },
                            () -> {
                                settleReActStreamUsage(accumulator);
                                ReActTurn turn = accumulator.toTurn();
                                logger.info("ReAct 流式回合完成: provider={}, model={}, finishReason={}, toolCalls={}, contentChars={}",
                                        provider.provider(),
                                        provider.model(),
                                        turn.finishReason(),
                                        turn.toolCalls().size(),
                                        turn.content().length());
                                onComplete.accept(turn);
                            }
                    );
            return new StreamHandle(subscription, () -> settleReActStreamUsage(accumulator));
        } catch (Exception exception) {
            usageQuotaService.abortReservation(reservation);
            throw exception;
        }
    }

    private WebClient buildClient(ModelProviderConfigService.ActiveProviderView provider) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.apiBaseUrl()));
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private void logProviderError(String message, Throwable error) {
        if (error instanceof WebClientResponseException responseException) {
            logger.warn("{}: status={}, body={}",
                    message,
                    responseException.getStatusCode(),
                    responseException.getResponseBodyAsString(),
                    responseException);
            return;
        }
        logger.warn("{}: {}", message, error.getMessage(), error);
    }

    private Map<String, Object> buildReActRequest(String model,
                                                  List<Map<String, Object>> messages,
                                                  List<AgentToolRegistry.AgentTool> tools,
                                                  int maxCompletionTokens,
                                                  boolean stream) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", stream);
        request.put("max_tokens", Math.max(maxCompletionTokens, 1));
        if (stream) {
            request.put("stream_options", Map.of("include_usage", true));
        }

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (tools != null && !tools.isEmpty()) {
            request.put("tools", buildOpenAiTools(tools));
            request.put("tool_choice", "auto");
        }
        return request;
    }

    private Map<String, Object> buildRequest(String model,
                                             String userMessage,
                                             String context,
                                             List<Map<String, String>> history) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessages(String userMessage,
                                                    String context,
                                                    List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        StringBuilder sysBuilder = new StringBuilder();
        if (promptCfg.getRules() != null) {
            sysBuilder.append(promptCfg.getRules()).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");
        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            sysBuilder.append(promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）").append("\n");
        }
        sysBuilder.append(refEnd);

        messages.add(Map.of("role", "system", "content", sysBuilder.toString()));
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    private Map<String, Object> newMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(maxChars, 0)) + "...";
    }

    private List<Map<String, Object>> buildOpenAiTools(List<AgentToolRegistry.AgentTool> tools) {
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        for (AgentToolRegistry.AgentTool tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parameters());

            Map<String, Object> toolSchema = new LinkedHashMap<>();
            toolSchema.put("type", "function");
            toolSchema.put("function", function);
            openAiTools.add(toolSchema);
        }
        return openAiTools;
    }

    private int estimateToolsTokens(List<AgentToolRegistry.AgentTool> tools) {
        int tokens = 0;
        for (AgentToolRegistry.AgentTool tool : tools) {
            tokens += usageQuotaService.estimateTextTokens(tool.name());
            tokens += usageQuotaService.estimateTextTokens(tool.description());
            try {
                tokens += usageQuotaService.estimateTextTokens(objectMapper.writeValueAsString(tool.parameters()));
            } catch (Exception ignored) {
                tokens += 80;
            }
        }
        return tokens;
    }

    private int estimateObjectMessagesTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (Map<String, Object> message : messages) {
            tokens += 8;
            tokens += usageQuotaService.estimateTextTokens(String.valueOf(message.getOrDefault("role", "")));
            tokens += usageQuotaService.estimateTextTokens(String.valueOf(message.getOrDefault("content", "")));
            Object reasoningContent = message.get("reasoning_content");
            if (reasoningContent != null) {
                tokens += usageQuotaService.estimateTextTokens(String.valueOf(reasoningContent));
            }
            Object toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                try {
                    tokens += usageQuotaService.estimateTextTokens(objectMapper.writeValueAsString(toolCalls));
                } catch (Exception ignored) {
                    tokens += 128;
                }
            }
            Object toolCallId = message.get("tool_call_id");
            if (toolCallId != null) {
                tokens += usageQuotaService.estimateTextTokens(String.valueOf(toolCallId));
            }
        }
        return Math.max(tokens, 1);
    }

    private ReActTurn parseReActTurn(String responseBody, int estimatedPromptTokens) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("ReAct 模型响应为空");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choice = root.path("choices").path(0);
            JsonNode messageNode = choice.path("message");
            if (!messageNode.isObject()) {
                throw new IllegalStateException("ReAct 模型响应缺少 message");
            }

            Map<String, Object> assistantMessage = objectMapper.convertValue(
                    messageNode,
                    new TypeReference<Map<String, Object>>() {
                    });
            assistantMessage.put("role", "assistant");
            if (!assistantMessage.containsKey("content") || assistantMessage.get("content") == null) {
                assistantMessage.put("content", "");
            }

            List<ToolCallDecision> toolCalls = new ArrayList<>();
            JsonNode toolCallsNode = messageNode.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode call : toolCallsNode) {
                    JsonNode function = call.path("function");
                    String name = function.path("name").asText("");
                    if (name.isBlank()) {
                        continue;
                    }
                    String argumentsJson = function.path("arguments").asText("{}");
                    Map<String, Object> arguments = objectMapper.readValue(
                            argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                            new TypeReference<Map<String, Object>>() {
                            });
                    toolCalls.add(new ToolCallDecision(call.path("id").asText(""), name, arguments));
                }
            }

            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(estimatedPromptTokens);
            int completionTokens = usage.path("completion_tokens").asInt(
                    usageQuotaService.estimateTextTokens(messageNode.path("content").asText(""))
                            + estimateObjectMessagesTokens(List.of(assistantMessage))
            );
            return new ReActTurn(
                    messageNode.path("content").asText("").trim(),
                    toolCalls,
                    assistantMessage,
                    choice.path("finish_reason").asText("unknown"),
                    promptTokens,
                    completionTokens
            );
        } catch (Exception exception) {
            throw new RuntimeException("解析 ReAct 模型响应失败", exception);
        }
    }

    private void processChunk(String rawChunk, StreamUsageTracker usageTracker, Consumer<String> onChunk) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(chunk);
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    usageTracker.promptTokens = usageNode.path("prompt_tokens").asInt(usageTracker.promptTokens);
                    usageTracker.completionTokens = usageNode.path("completion_tokens").asInt(usageTracker.completionTokens);
                }

                JsonNode choiceNode = node.path("choices").path(0);
                JsonNode finishReasonNode = choiceNode.path("finish_reason");
                if (!finishReasonNode.isMissingNode() && !finishReasonNode.isNull()) {
                    String finishReason = finishReasonNode.asText("");
                    if (!finishReason.isBlank()) {
                        usageTracker.finishReason = finishReason;
                    }
                }

                String content = node.path("choices")
                        .path(0)
                        .path("delta")
                        .path("content")
                        .asText("");
                if (!content.isEmpty()) {
                    usageTracker.responseContent.append(content);
                    onChunk.accept(content);
                }
            }
        } catch (Exception exception) {
            logger.error("处理模型响应数据块失败: {}", exception.getMessage(), exception);
        }
    }

    private void processReActStreamChunk(String rawChunk,
                                         ReActStreamAccumulator accumulator,
                                         Consumer<String> onChunk) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(chunk);
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    accumulator.promptTokens = usageNode.path("prompt_tokens").asInt(accumulator.promptTokens);
                    accumulator.completionTokens = usageNode.path("completion_tokens").asInt(accumulator.completionTokens);
                }

                JsonNode choiceNode = node.path("choices").path(0);
                if (!choiceNode.isObject()) {
                    continue;
                }

                JsonNode finishReasonNode = choiceNode.path("finish_reason");
                if (!finishReasonNode.isMissingNode() && !finishReasonNode.isNull()) {
                    String finishReason = finishReasonNode.asText("");
                    if (!finishReason.isBlank()) {
                        accumulator.finishReason = finishReason;
                    }
                }

                JsonNode delta = choiceNode.path("delta");
                String reasoningContent = delta.path("reasoning_content").asText("");
                if (!reasoningContent.isEmpty()) {
                    accumulator.reasoningContent.append(reasoningContent);
                }

                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    accumulator.content.append(content);
                    onChunk.accept(content);
                }

                JsonNode toolCallsNode = delta.path("tool_calls");
                if (toolCallsNode.isArray()) {
                    for (JsonNode toolCallDelta : toolCallsNode) {
                        accumulator.appendToolCallDelta(toolCallDelta);
                    }
                }
            }
        } catch (Exception exception) {
            logger.error("处理 ReAct 流式响应数据块失败: {}", exception.getMessage(), exception);
        }
    }

    private List<String> extractPayloads(String rawChunk) {
        List<String> payloads = new ArrayList<>();
        if (rawChunk == null || rawChunk.isBlank()) {
            return payloads;
        }

        String trimmed = rawChunk.trim();
        for (String line : trimmed.split("\\r?\\n")) {
            String payload = line.trim();
            if (payload.isEmpty() || payload.startsWith(":")) {
                continue;
            }
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (!payload.isEmpty()) {
                payloads.add(payload);
            }
        }

        if (payloads.isEmpty()) {
            payloads.add(trimmed);
        }
        return payloads;
    }

    private void settleUsage(StreamUsageTracker usageTracker) {
        if (usageTracker == null || usageTracker.settled) {
            return;
        }

        usageTracker.settled = true;
        int actualPromptTokens = usageTracker.promptTokens > 0
                ? usageTracker.promptTokens
                : usageTracker.estimatedPromptTokens;
        int actualCompletionTokens = usageTracker.completionTokens > 0
                ? usageTracker.completionTokens
                : usageQuotaService.estimateTextTokens(usageTracker.responseContent.toString());

        usageQuotaService.settleReservation(usageTracker.reservation, actualPromptTokens + actualCompletionTokens);
    }

    private void settleReActStreamUsage(ReActStreamAccumulator accumulator) {
        if (accumulator == null || accumulator.settled) {
            return;
        }

        accumulator.settled = true;
        int actualPromptTokens = accumulator.promptTokens > 0
                ? accumulator.promptTokens
                : accumulator.estimatedPromptTokens;
        int actualCompletionTokens = accumulator.completionTokens > 0
                ? accumulator.completionTokens
                : usageQuotaService.estimateTextTokens(accumulator.content.toString())
                + estimateObjectMessagesTokens(List.of(accumulator.assistantMessage()));
        usageQuotaService.settleReservation(accumulator.reservation, actualPromptTokens + actualCompletionTokens);
    }

    private static final class StreamUsageTracker {
        private final UsageQuotaService.TokenReservationBundle reservation;
        private final int estimatedPromptTokens;
        private final StringBuilder responseContent = new StringBuilder();
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile String finishReason;
        private volatile boolean settled;

        private StreamUsageTracker(UsageQuotaService.TokenReservationBundle reservation, int estimatedPromptTokens) {
            this.reservation = reservation;
            this.estimatedPromptTokens = estimatedPromptTokens;
        }
    }

    private static final class ReActStreamAccumulator {
        private final UsageQuotaService.TokenReservationBundle reservation;
        private final int estimatedPromptTokens;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoningContent = new StringBuilder();
        private final Map<Integer, StreamingToolCall> toolCalls = new LinkedHashMap<>();
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile String finishReason;
        private volatile boolean settled;

        private ReActStreamAccumulator(UsageQuotaService.TokenReservationBundle reservation, int estimatedPromptTokens) {
            this.reservation = reservation;
            this.estimatedPromptTokens = estimatedPromptTokens;
        }

        private void appendToolCallDelta(JsonNode delta) {
            int index = delta.path("index").asInt(toolCalls.size());
            StreamingToolCall toolCall = toolCalls.computeIfAbsent(index, ignored -> new StreamingToolCall());
            String id = delta.path("id").asText("");
            if (!id.isBlank()) {
                toolCall.id = id;
            }
            String type = delta.path("type").asText("");
            if (!type.isBlank()) {
                toolCall.type = type;
            }
            JsonNode function = delta.path("function");
            if (function.isObject()) {
                String name = function.path("name").asText("");
                if (!name.isBlank()) {
                    toolCall.name.append(name);
                }
                String arguments = function.path("arguments").asText("");
                if (!arguments.isEmpty()) {
                    toolCall.arguments.append(arguments);
                }
            }
        }

        private Map<String, Object> assistantMessage() {
            Map<String, Object> message = new LinkedHashMap<>();
            List<Map<String, Object>> serializedToolCalls = serializedToolCalls();
            message.put("role", "assistant");
            if (!serializedToolCalls.isEmpty()) {
                String assistantContent = content.toString();
                message.put("content", assistantContent.isBlank() ? null : assistantContent);
                message.put("tool_calls", serializedToolCalls);
            } else {
                message.put("content", content.toString());
            }
            if (!reasoningContent.isEmpty()) {
                message.put("reasoning_content", reasoningContent.toString());
            }
            return message;
        }

        private List<Map<String, Object>> serializedToolCalls() {
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (Map.Entry<Integer, StreamingToolCall> entry : toolCalls.entrySet()) {
                StreamingToolCall toolCall = entry.getValue();
                if (toolCall.name.isEmpty()) {
                    continue;
                }
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", toolCall.name.toString());
                function.put("arguments", toolCall.arguments.isEmpty() ? "{}" : toolCall.arguments.toString());

                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", toolCall.id == null || toolCall.id.isBlank() ? "call_" + entry.getKey() : toolCall.id);
                call.put("type", toolCall.type == null || toolCall.type.isBlank() ? "function" : toolCall.type);
                call.put("function", function);
                serialized.add(call);
            }
            return serialized;
        }

        private ReActTurn toTurn() {
            Map<String, Object> assistantMessage = assistantMessage();
            List<ToolCallDecision> decisions = new ArrayList<>();
            for (Map<String, Object> item : serializedToolCalls()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> function = (Map<String, Object>) item.get("function");
                String argumentsJson = String.valueOf(function.getOrDefault("arguments", "{}"));
                Map<String, Object> arguments;
                try {
                    arguments = new ObjectMapper().readValue(
                            argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                            new TypeReference<Map<String, Object>>() {
                            });
                } catch (Exception ignored) {
                    arguments = Map.of();
                }
                decisions.add(new ToolCallDecision(
                        String.valueOf(item.getOrDefault("id", "")),
                        String.valueOf(function.getOrDefault("name", "")),
                        arguments
                ));
            }
            return new ReActTurn(
                    content.toString().trim(),
                    decisions,
                    assistantMessage,
                    finishReason == null || finishReason.isBlank() ? "unknown" : finishReason,
                    promptTokens > 0 ? promptTokens : estimatedPromptTokens,
                    completionTokens > 0 ? completionTokens : DEFAULT_REACT_MAX_COMPLETION_TOKENS
            );
        }
    }

    private static final class StreamingToolCall {
        private String id;
        private String type;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    public record StreamCompletion(
            String finishReason,
            int promptTokens,
            int completionTokens,
            int responseChars
    ) {
    }

    public record ToolCallDecision(
            String id,
            String name,
            Map<String, Object> arguments
    ) {
    }

    public record ReActTurn(
            String content,
            List<ToolCallDecision> toolCalls,
            Map<String, Object> assistantMessage,
            String finishReason,
            int promptTokens,
            int completionTokens
    ) {
    }

    public static final class StreamHandle {
        private final Disposable subscription;
        private final Runnable onCancel;

        private StreamHandle(Disposable subscription, Runnable onCancel) {
            this.subscription = subscription;
            this.onCancel = onCancel;
        }

        public void cancel() {
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
            }
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }
}
