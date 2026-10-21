package com.smartrag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrag.entity.SearchResult;
import com.smartrag.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 聊天处理服务
 * 负责处理WebSocket聊天消息和管理对话历史
 * 
 */
@Service
public class ChatHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private static final int MAX_CONTEXT_SNIPPET_LEN = 300;
    private static final int MAX_MATCHED_CHUNK_LEN = 800;
    private static final int MAX_EVIDENCE_SNIPPET_LEN = 160;
    private static final int GENERATION_COMPLETION_TIMEOUT_SECONDS = 120;
    private static final int MAX_REACT_ROUNDS = 4;
    private static final int MAX_REACT_TOOL_CALLS = 8;
    private static final int REACT_MAX_COMPLETION_TOKENS = 2000;
    private final RedisTemplate<String, String> redisTemplate;
    private final HybridSearchService searchService;
    private final LlmProviderRouter llmProviderRouter;
    private final RateLimitService rateLimitService;
    private final ConversationService conversationService;
    private final ChatGenerationStateService chatGenerationStateService;
    private final ChatSessionRegistry chatSessionRegistry;
    private final AgentToolRegistry agentToolRegistry;
    private final ThreadPoolTaskExecutor chatMonitorExecutor;
    private final ObjectMapper objectMapper;
    
    // 用于存储每次生成任务的完整响应
    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    // 用于跟踪每次生成任务的响应完成状态
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    // 用于持有正在进行中的 LLM 流，支持主动取消上游请求
    private final Map<String, LlmProviderRouter.StreamHandle> activeStreams = new ConcurrentHashMap<>();
    // 停止标志 - 简单方案
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    // 用于标记已经取消的生成任务，防止后续又被落成 completed
    private final KeySetView<String, Boolean> cancelledGenerations = ConcurrentHashMap.newKeySet();
    // 用于存储每次生成任务的引用映射：generationId -> {referenceNumber -> detail}
    private final Map<String, Map<Integer, ReferenceInfo>> generationReferenceMappings = new ConcurrentHashMap<>();

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                      HybridSearchService searchService,
                      LlmProviderRouter llmProviderRouter,
                      RateLimitService rateLimitService,
                      ConversationService conversationService,
                      ChatGenerationStateService chatGenerationStateService,
                      ChatSessionRegistry chatSessionRegistry,
                      AgentToolRegistry agentToolRegistry,
                      ObjectMapper objectMapper,
                      @Qualifier("chatMonitorExecutor") ThreadPoolTaskExecutor chatMonitorExecutor) {
        this.redisTemplate = redisTemplate;
        this.searchService = searchService;
        this.llmProviderRouter = llmProviderRouter;
        this.rateLimitService = rateLimitService;
        this.conversationService = conversationService;
        this.chatGenerationStateService = chatGenerationStateService;
        this.chatSessionRegistry = chatSessionRegistry;
        this.agentToolRegistry = agentToolRegistry;
        this.objectMapper = objectMapper;
        this.chatMonitorExecutor = chatMonitorExecutor;
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        logger.info("开始处理消息，用户ID: {}, 会话ID: {}", userId, session.getId());
        String conversationId = null;
        String generationId = null;
        try {
            rateLimitService.checkChatByUser(userId);

            // 1. 获取或创建会话 ID
            conversationId = getOrCreateConversationId(userId);
            conversationService.ensureConversationSession(Long.parseLong(userId), conversationId, userMessage);
            ChatGenerationStateService.GenerationSnapshot generation =
                    chatGenerationStateService.createGeneration(userId, conversationId, userMessage);
            generationId = generation.generationId();
            final String finalConversationId = conversationId;
            final String finalGenerationId = generationId;
            logger.info("会话ID: {}, 用户ID: {}", conversationId, userId);
            sendGenerationStart(userId, finalGenerationId, finalConversationId);

            // 为当前生成任务创建响应构建器
            responseBuilders.put(finalGenerationId, new StringBuilder());
            // 创建一个CompletableFuture来跟踪响应完成状态
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(finalGenerationId, responseFuture);

            // 2. 获取对话历史
            List<Map<String, String>> history = getConversationHistory(conversationId);
            logger.debug("获取到 {} 条历史对话", history.size());

            // 3. 异步执行 ReAct 决策循环：模型按需返回 tool_calls，避免在 WebSocket 处理线程上阻塞 90s+ 的工具流
            try {
                chatMonitorExecutor.execute(() ->
                        runReActLoopSafely(userId, userMessage, finalConversationId, finalGenerationId, history, responseFuture));
            } catch (RejectedExecutionException ex) {
                logger.warn("聊天处理线程池已满，generationId: {}", finalGenerationId);
                RuntimeException busyException = new RuntimeException("系统繁忙，请稍后重试");
                chatGenerationStateService.markFailed(finalGenerationId, busyException.getMessage());
                handleError(userId, finalGenerationId, busyException);
                sendCompletionNotification(userId, finalGenerationId, finalConversationId, true, false);
                cleanupGenerationState(finalGenerationId, ex);
            }

        } catch (RateLimitExceededException e) {
            sendRateLimitMessage(userId, null, e);
        } catch (Exception e) {
            logger.error("处理消息错误: {}", e.getMessage(), e);
            if (generationId != null) {
                chatGenerationStateService.markFailed(generationId, e.getMessage());
                cleanupGenerationState(generationId, e);
            }
            handleError(userId, generationId, e);
        }
    }

    private void runReActLoopSafely(String userId,
                                    String userMessage,
                                    String conversationId,
                                    String generationId,
                                    List<Map<String, String>> history,
                                    CompletableFuture<String> responseFuture) {
        try {
            runReActLoop(userId, userMessage, conversationId, generationId, history, responseFuture);
        } catch (Exception e) {
            logger.error("ReAct 循环执行失败: generationId={}", generationId, e);
            chatGenerationStateService.markFailed(generationId, e.getMessage());
            handleError(userId, generationId, e);
            sendCompletionNotification(userId, generationId, conversationId, true, false);
            cleanupGenerationState(generationId, e);
        }
    }

    private void runReActLoop(String userId,
                              String userMessage,
                              String conversationId,
                              String generationId,
                              List<Map<String, String>> history,
                              CompletableFuture<String> responseFuture) {
        List<Map<String, Object>> messages = llmProviderRouter.buildReActMessages(
                userMessage,
                "",
                history,
                buildRecentFeedbackGuidance(userId)
        );
        int executedToolCalls = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int round = 1; round <= MAX_REACT_ROUNDS; round++) {
            if (finishCancelledGeneration(generationId, responseFuture, responseBuilders.get(generationId))) {
                return;
            }

            LlmProviderRouter.ReActTurn turn = streamReActTurnBlocking(
                    userId, conversationId, generationId, messages, agentToolRegistry.getTools());
            if (turn == null) {
                // 上游 stream 被取消（如用户点 stop），保证内存映射被回收
                cleanupGenerationState(generationId, null);
                return;
            }
            totalPromptTokens += turn.promptTokens();
            totalCompletionTokens += turn.completionTokens();

            if (turn.toolCalls().isEmpty()) {
                finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                        responseBuilders.get(generationId),
                        new LlmProviderRouter.StreamCompletion(turn.finishReason(), totalPromptTokens, totalCompletionTokens, turn.content().length()));
                return;
            }

            messages.add(turn.assistantMessage());
            for (LlmProviderRouter.ToolCallDecision toolCall : turn.toolCalls()) {
                ExecutedToolResult executedToolResult;
                if (executedToolCalls >= MAX_REACT_TOOL_CALLS) {
                    executedToolResult = new ExecutedToolResult(
                            "工具调用预算已用尽，本次工具未执行。请基于已有 tool 结果给出最终回答。",
                            false
                    );
                    sendToolCallStatus(userId, generationId, conversationId, toolCall, "failed");
                } else {
                    executedToolResult = executeToolForReAct(userId, userMessage, generationId, conversationId, toolCall);
                    executedToolCalls++;
                }
                messages.add(toolMessage(toolCall.id(), executedToolResult.content()));
                if (executedToolResult.streamedToUser()) {
                    finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                            responseBuilders.get(generationId),
                            new LlmProviderRouter.StreamCompletion(
                                    "tool_streamed",
                                    totalPromptTokens,
                                    totalCompletionTokens,
                                    responseBuilders.get(generationId) != null ? responseBuilders.get(generationId).length() : 0
                            ));
                    return;
                }
            }
        }

        messages.add(Map.of(
                "role", "user",
                "content", "ReAct 轮次预算已用尽，请不要再调用工具，直接基于已有 tool 结果给出最终回答。"
        ));
        LlmProviderRouter.ReActTurn finalTurn = streamReActTurnBlocking(
                userId, conversationId, generationId, messages, List.of());
        if (finalTurn == null) {
            cleanupGenerationState(generationId, null);
            return;
        }
        totalPromptTokens += finalTurn.promptTokens();
        totalCompletionTokens += finalTurn.completionTokens();
        finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                responseBuilders.get(generationId),
                new LlmProviderRouter.StreamCompletion(finalTurn.finishReason(), totalPromptTokens, totalCompletionTokens, finalTurn.content().length()));
    }

    private ExecutedToolResult executeToolForReAct(String userId,
                                                   String userMessage,
                                                   String generationId,
                                                   String conversationId,
                                                   LlmProviderRouter.ToolCallDecision toolCall) {
        sendToolCallStatus(userId, generationId, conversationId, toolCall, "executing");
        AtomicBoolean summaryStreamStarted = new AtomicBoolean(false);
        try {
            logger.info("ReAct 执行 Agent Tool: name={}, userId={}, generationId={}, toolCallId={}, args={}",
                    toolCall.name(), userId, generationId, toolCall.id(), toolCall.arguments());
            Consumer<String> toolChunkConsumer = "generate_summary".equals(toolCall.name())
                    ? chunk -> {
                        if (chunk == null || chunk.isEmpty()) {
                            return;
                        }
                        if (summaryStreamStarted.compareAndSet(false, true)) {
                            appendStreamChunk(userId, generationId, conversationId, "\n\n");
                        }
                        appendStreamChunk(userId, generationId, conversationId, chunk);
                    }
                    : null;
            AgentToolRegistry.ToolExecutionResult toolResult =
                    agentToolRegistry.executeTool(toolCall.name(), toolCall.arguments(), userId, toolChunkConsumer);

            // search_knowledge 返回的 SearchResult 列表与模型 prompt 中的 [N] 编号一一对应，
            // 必须把它落到 generationReferenceMappings 里，否则前端点击引用拿不到 MD5/页码。
            if ("search_knowledge".equals(toolCall.name())) {
                replaceReferencesFromSearchTool(generationId, userMessage, toolResult);
            }

            String content = toolResult.content();
            if (content == null || content.isBlank()) {
                content = "工具 " + toolCall.name() + " 执行成功，但没有返回可展示内容。";
            }
            sendToolCallStatus(userId, generationId, conversationId, toolCall, "success");
            return new ExecutedToolResult(content, toolResult.streamedToUser());
        } catch (Exception exception) {
            logger.warn("ReAct Agent Tool 执行失败，作为 tool message 返回模型: name={}, generationId={}",
                    toolCall.name(), generationId, exception);
            sendToolCallStatus(userId, generationId, conversationId, toolCall, "failed");
            // generate_summary 已经把部分摘要流给前端，再让模型重写会拼出"半个旧摘要 + 新摘要"。
            // 直接以失败提示收尾，让 ReAct 循环立即 finalize，避免数据不一致。
            if ("generate_summary".equals(toolCall.name()) && summaryStreamStarted.get()) {
                appendStreamChunk(userId, generationId, conversationId,
                        "\n\n（摘要流式生成中断：" + exception.getMessage() + "）");
                return new ExecutedToolResult(
                        "工具 " + toolCall.name() + " 已部分流式输出后失败: " + exception.getMessage(),
                        true
                );
            }
            return new ExecutedToolResult("工具 " + toolCall.name() + " 执行失败: " + exception.getMessage(), false);
        }
    }

    private void replaceReferencesFromSearchTool(String generationId,
                                                 String userMessage,
                                                 AgentToolRegistry.ToolExecutionResult toolResult) {
        if (toolResult == null || toolResult.data() == null) {
            return;
        }
        Object resultsObj = toolResult.data().get("results");
        if (!(resultsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return;
        }

        Map<Integer, ReferenceInfo> mapping = new HashMap<>();
        int referenceNumber = 1;
        for (Object item : rawList) {
            if (!(item instanceof SearchResult result) || result.getFileMd5() == null) {
                continue;
            }
            String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
            mapping.put(referenceNumber, buildReferenceInfo(result, fileLabel, userMessage));
            referenceNumber++;
        }
        if (mapping.isEmpty()) {
            return;
        }
        // 模型每次 search_knowledge 都会拿到 [1]..[K] 重新编号，因此按"覆盖"语义保存最新一次的引用映射。
        generationReferenceMappings.put(generationId, mapping);
        chatGenerationStateService.updateReferenceMappings(generationId, toSerializableReferenceMappings(mapping));
        logger.info("ReAct search_knowledge 引用映射已刷新: generationId={}, count={}", generationId, mapping.size());
    }

    private record ExecutedToolResult(String content, boolean streamedToUser) {
    }

    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private LlmProviderRouter.ReActTurn streamReActTurnBlocking(String userId,
                                                                String conversationId,
                                                                String generationId,
                                                                List<Map<String, Object>> messages,
                                                                List<AgentToolRegistry.AgentTool> tools) {
        CompletableFuture<LlmProviderRouter.ReActTurn> turnFuture = new CompletableFuture<>();
        LlmProviderRouter.StreamHandle streamHandle = llmProviderRouter.streamReActTurn(
                userId,
                messages,
                tools,
                REACT_MAX_COMPLETION_TOKENS,
                chunk -> appendStreamChunk(userId, generationId, conversationId, chunk),
                turnFuture::completeExceptionally,
                turnFuture::complete
        );
        activeStreams.put(generationId, streamHandle);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENERATION_COMPLETION_TIMEOUT_SECONDS);
        try {
            while (true) {
                if (isGenerationCancelled(generationId)) {
                    streamHandle.cancel();
                    return null;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    streamHandle.cancel();
                    throw new RuntimeException("模型响应超时，请稍后重试");
                }
                try {
                    long waitMillis = Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 200L);
                    return turnFuture.get(Math.max(waitMillis, 1L), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 短轮询用于及时响应用户停止生成。
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            streamHandle.cancel();
            throw new RuntimeException("模型响应被中断", exception);
        } catch (ExecutionException exception) {
            streamHandle.cancel();
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("ReAct 流式模型回合调用失败", cause);
        } finally {
            activeStreams.remove(generationId, streamHandle);
        }
    }

    private void appendStreamChunk(String userId, String generationId, String conversationId, String chunk) {
        if (chunk == null || chunk.isEmpty() || isGenerationCancelled(generationId)) {
            return;
        }
        StringBuilder responseBuilder = responseBuilders.get(generationId);
        if (responseBuilder != null) {
            responseBuilder.append(chunk);
        }
        chatGenerationStateService.appendChunk(generationId, chunk);
        sendResponseChunk(userId, generationId, conversationId, chunk);
    }

    private void submitCompletionMonitor(String userId, String userMessage, String conversationId, String generationId,
                                         CompletableFuture<String> responseFuture) {
        try {
            chatMonitorExecutor.execute(() -> {
                try {
                    if (finishCancelledGeneration(generationId, responseFuture, responseBuilders.get(generationId))) {
                        return;
                    }

                    responseFuture.get(GENERATION_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    if (finishCancelledGeneration(generationId, responseFuture, responseBuilders.get(generationId))) {
                        return;
                    }
                    RuntimeException exception = new RuntimeException("模型响应超时，请稍后重试");
                    if (responseFuture.completeExceptionally(exception)) {
                        handleError(userId, generationId, exception);
                        sendCompletionNotification(userId, generationId, conversationId, true, false);
                        chatGenerationStateService.markFailed(generationId, exception.getMessage());
                        cleanupGenerationState(generationId, exception);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ignored) {
                    // 上游错误分支已经处理并完成了当前 future，这里只负责等待兜底。
                } catch (Exception e) {
                    logger.error("检查响应完成时出错: {}", e.getMessage(), e);
                    if (responseFuture.completeExceptionally(e)) {
                        chatGenerationStateService.markFailed(generationId, e.getMessage());
                        cleanupGenerationState(generationId, e);
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            logger.warn("聊天监控线程池已满，generationId: {}", generationId);
            RuntimeException busyException = new RuntimeException("系统繁忙，请稍后重试");
            handleError(userId, generationId, busyException);
            chatGenerationStateService.markFailed(generationId, busyException.getMessage());
            cleanupGenerationState(generationId, ex);
        }
    }

    private void finalizeResponse(String userId, String userMessage, String conversationId, String generationId,
                                  CompletableFuture<String> responseFuture,
                                  StringBuilder responseBuilder,
                                  LlmProviderRouter.StreamCompletion completion) {
        if (finishCancelledGeneration(generationId, responseFuture, responseBuilder)) {
            return;
        }
        if (responseBuilder == null) {
            if (responseFuture.isDone() || !responseFutures.containsKey(generationId)) {
                return;
            }
            RuntimeException exception = new RuntimeException("响应构建器为空");
            if (responseFuture.completeExceptionally(exception)) {
                handleError(userId, generationId, exception);
                sendCompletionNotification(userId, generationId, conversationId, true, false);
                chatGenerationStateService.markFailed(generationId, exception.getMessage());
                cleanupGenerationState(generationId, exception);
            }
            return;
        }
        String completeResponse = responseBuilder.toString();
        if (completeResponse.isBlank()) {
            RuntimeException exception = new RuntimeException("模型未返回有效内容，请稍后重试");
            if (responseFuture.completeExceptionally(exception)) {
                handleError(userId, generationId, exception);
                sendCompletionNotification(userId, generationId, conversationId, true, false);
                chatGenerationStateService.markFailed(generationId, exception.getMessage());
                cleanupGenerationState(generationId, exception);
            }
            return;
        }
        if (!responseFuture.complete(completeResponse)) {
            return;
        }
        logger.info("模型回答收尾: generationId={}, conversationId={}, answerChars={}, finishReason={}, promptTokens={}, completionTokens={}",
                generationId,
                conversationId,
                completeResponse.length(),
                completion != null && completion.finishReason() != null ? completion.finishReason() : "unknown",
                completion != null ? completion.promptTokens() : 0,
                completion != null ? completion.completionTokens() : 0);
        Map<Integer, ReferenceInfo> referenceMappings = generationReferenceMappings.get(generationId);
        // 先把消息事务性地落 MySQL；只有 MySQL 成功后才写 Redis 短期会话历史，
        // 否则两个数据源会出现一边有记录、一边没有的不一致状态。
        boolean persisted = persistConversation(userId, userMessage, completeResponse, conversationId, referenceMappings);
        if (persisted) {
            updateConversationHistory(conversationId, userMessage, completeResponse, referenceMappings);
        } else {
            logger.warn("MySQL 落库失败，跳过 Redis 会话历史写入以保持两端一致: generationId={}, conversationId={}",
                    generationId, conversationId);
        }
        chatGenerationStateService.markCompleted(generationId, toSerializableReferenceMappings(referenceMappings));
        sendCompletionNotification(userId, generationId, conversationId, false, !persisted);
        logger.info("对话存储信息 - Redis键: {}, 值: {}", "user:" + userId + ":current_conversation", conversationId);
        cleanupGenerationState(generationId, null);
        logger.info("消息处理完成，用户ID: {}", userId);
    }

    private boolean persistConversation(String userId, String userMessage, String completeResponse, String conversationId,
                                        Map<Integer, ReferenceInfo> referenceMappings) {
        try {
            Long userIdLong = Long.parseLong(userId);
            conversationService.recordConversation(
                    userIdLong,
                    userMessage,
                    completeResponse,
                    conversationId,
                    toSerializableReferenceMappings(referenceMappings)
            );
            return true;
        } catch (Exception e) {
            logger.error("持久化对话历史失败: userId={}, conversationId={}", userId, conversationId, e);
            return false;
        }
    }

    private void cleanupGenerationState(String generationId, Throwable throwable) {
        responseBuilders.remove(generationId);
        generationReferenceMappings.remove(generationId);
        stopFlags.remove(generationId);
        activeStreams.remove(generationId);
        cancelledGenerations.remove(generationId);
        CompletableFuture<String> future = responseFutures.remove(generationId);
        if (throwable != null && future != null && !future.isDone()) {
            future.completeExceptionally(throwable);
        }
    }

    private boolean finishCancelledGeneration(String generationId,
                                              CompletableFuture<String> responseFuture,
                                              StringBuilder responseBuilder) {
        if (!isGenerationCancelled(generationId)) {
            return false;
        }

        if (!responseFuture.isDone()) {
            responseFuture.complete(responseBuilder != null ? responseBuilder.toString() : "");
        }
        cleanupGenerationState(generationId, null);
        logger.info("生成任务已取消并停止后续收尾逻辑: generationId={}", generationId);
        return true;
    }

    private boolean isGenerationCancelled(String generationId) {
        return generationId != null && (cancelledGenerations.contains(generationId) || Boolean.TRUE.equals(stopFlags.get(generationId)));
    }

    private String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(key);
        
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(7));
            logger.info("为用户 {} 创建新的会话ID: {}", userId, conversationId);
        } else {
            logger.info("获取到用户 {} 的现有会话ID: {}", userId, conversationId);
        }
        
        return conversationId;
    }

    private List<Map<String, String>> getConversationHistory(String conversationId) {
        List<Map<String, Object>> records = getConversationHistoryRecords(conversationId);
        List<Map<String, String>> history = new ArrayList<>();
        for (Map<String, Object> message : records) {
            Map<String, String> normalized = new HashMap<>();
            normalized.put("role", String.valueOf(message.getOrDefault("role", "")));
            normalized.put("content", String.valueOf(message.getOrDefault("content", "")));
            Object timestamp = message.get("timestamp");
            if (timestamp != null) {
                normalized.put("timestamp", String.valueOf(timestamp));
            }
            history.add(normalized);
        }
        return history;
    }

    private String buildRecentFeedbackGuidance(String userId) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        try {
            Map<Object, Object> feedbackEntries = redisTemplate.opsForHash().entries("feedback:" + userId);
            if (feedbackEntries == null || feedbackEntries.isEmpty()) {
                return "";
            }

            StringBuilder guidance = new StringBuilder("近期用户对回答的显式反馈如下，按时间倒序排列，越靠前越新。")
                    .append("后续回答需要参考这些偏好：good 表示用户认可这类回答方式，bad 表示需要避免类似问题；")
                    .append("如果同一轮回答既有 good 又有 bad，以最新一条为准。\n");
            feedbackEntries.entrySet().stream()
                    .sorted(Comparator.comparingLong(entry -> -parseFeedbackTimestamp(entry.getKey())))
                    .limit(5)
                    .forEach(entry -> guidance.append("- ")
                            .append("feedbackTime=")
                            .append(entry.getKey())
                            .append("; ")
                            .append(entry.getValue())
                            .append("\n"));
            return guidance.toString().trim();
        } catch (Exception exception) {
            logger.warn("读取用户反馈上下文失败: userId={}", userId, exception);
            return "";
        }
    }

    private long parseFeedbackTimestamp(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private List<Map<String, Object>> getConversationHistoryRecords(String conversationId) {
        String key = "conversation:" + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        try {
            if (json == null) {
                logger.debug("会话 {} 没有历史记录", conversationId);
                return new ArrayList<>();
            }

            List<Map<String, Object>> history = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            logger.debug("读取到会话 {} 的 {} 条历史记录", conversationId, history.size());
            return history;
        } catch (JsonProcessingException e) {
            logger.error("解析对话历史出错: {}, 会话ID: {}", e.getMessage(), conversationId, e);
            return new ArrayList<>();
        }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String response,
                                           Map<Integer, ReferenceInfo> referenceMapping) {
        String key = "conversation:" + conversationId;
        List<Map<String, Object>> history = getConversationHistoryRecords(conversationId);
        
        // 获取当前时间戳
        String currentTimestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        
        // 添加用户消息（带时间戳）
        Map<String, Object> userMsgMap = new HashMap<>();
        userMsgMap.put("role", "user");
        userMsgMap.put("content", userMessage);
        userMsgMap.put("timestamp", currentTimestamp);
        history.add(userMsgMap);

        // 添加助手回复（带时间戳）
        Map<String, Object> assistantMsgMap = new HashMap<>();
        assistantMsgMap.put("role", "assistant");
        assistantMsgMap.put("content", response);
        assistantMsgMap.put("timestamp", currentTimestamp);
        if (referenceMapping != null && !referenceMapping.isEmpty()) {
            assistantMsgMap.put("referenceMappings", toSerializableReferenceMappings(referenceMapping));
        }
        history.add(assistantMsgMap);
        
        // 限制历史记录长度，保留最近的20条消息
        if (history.size() > 20) {
            history = history.subList(history.size() - 20, history.size());
        }
        
        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(key, json, Duration.ofDays(7));
            logger.debug("更新会话历史，会话ID: {}, 总消息数: {}", conversationId, history.size());
        } catch (JsonProcessingException e) {
            logger.error("序列化对话历史出错: {}, 会话ID: {}", e.getMessage(), conversationId, e);
        }
    }

    private Map<String, Map<String, Object>> toSerializableReferenceMappings(Map<Integer, ReferenceInfo> referenceMapping) {
        Map<String, Map<String, Object>> serialized = new HashMap<>();
        if (referenceMapping == null || referenceMapping.isEmpty()) {
            return serialized;
        }
        for (Map.Entry<Integer, ReferenceInfo> entry : referenceMapping.entrySet()) {
            ReferenceInfo detail = entry.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("fileMd5", detail.fileMd5());
            item.put("fileName", detail.fileName());
            item.put("pageNumber", detail.pageNumber());
            item.put("anchorText", detail.anchorText());
            item.put("retrievalMode", detail.retrievalMode());
            item.put("retrievalLabel", detail.retrievalLabel());
            item.put("retrievalQuery", detail.retrievalQuery());
            item.put("matchedChunkText", detail.matchedChunkText());
            item.put("evidenceSnippet", detail.evidenceSnippet());
            item.put("score", detail.score());
            item.put("chunkId", detail.chunkId());
            serialized.put(String.valueOf(entry.getKey()), item);
        }
        return serialized;
    }

    private String buildContext(List<SearchResult> searchResults, String generationId, String userMessage) {
        if (searchResults == null || searchResults.isEmpty()) {
            // 返回空字符串，让 LLM provider 按"无检索结果"逻辑处理
            return "";
        }

        // 创建当前生成任务的引用映射
        Map<Integer, ReferenceInfo> referenceMapping = new HashMap<>();

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            String snippet = result.getTextContent();
            if (snippet.length() > MAX_CONTEXT_SNIPPET_LEN) {
                snippet = snippet.substring(0, MAX_CONTEXT_SNIPPET_LEN) + "…";
            }
            String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
            String fileMd5 = result.getFileMd5();

            // 格式：[1] (test1.txt | 第5页) 文件内容... 或 [1] (test1.txt) 文件内容...
            // 有页码时显示页码，方便AI引用
            Integer pageNum = result.getPageNumber();
            if (pageNum != null && pageNum > 0) {
                context.append(String.format("[%d] (%s | 第%d页) %s\n", i + 1, fileLabel, pageNum, snippet));
            } else {
                context.append(String.format("[%d] (%s) %s\n", i + 1, fileLabel, snippet));
            }

            // 保存引用编号到MD5的映射
            if (fileMd5 != null) {
                ReferenceInfo detail = buildReferenceInfo(result, fileLabel, userMessage);
                referenceMapping.put(i + 1, detail);
                // 详细日志：记录每个引用编号的映射关系
                logger.info("引用映射: generationId={}, 引用编号#{}={}, 文件名={}, MD5={}, page={}, retrievalMode={}, chunkId={}",
                    generationId, i + 1, fileLabel, fileMd5, result.getPageNumber(), detail.retrievalMode(), detail.chunkId());
            }
        }

        // 保存当前生成任务的引用映射
        generationReferenceMappings.put(generationId, referenceMapping);
        chatGenerationStateService.updateReferenceMappings(generationId, toSerializableReferenceMappings(referenceMapping));
        logger.info("保存生成任务 {} 的引用映射，共 {} 条: {}", generationId, referenceMapping.size(), referenceMapping);

        return context.toString();
    }

    private void sendGenerationStart(String userId, String generationId, String conversationId) {
        chatSessionRegistry.sendJsonToUser(userId, Map.of(
                "type", "start",
                "generationId", generationId,
                "conversationId", conversationId,
                "timestamp", System.currentTimeMillis()
        ));
    }

    private void sendResponseChunk(String userId, String generationId, String conversationId, String chunk) {
        if (Boolean.TRUE.equals(stopFlags.get(generationId))) {
            logger.debug("检测到停止标志，跳过发送响应块: generationId={}", generationId);
            return;
        }

        chatSessionRegistry.sendJsonToUser(userId, Map.of(
                "type", "chunk",
                "generationId", generationId,
                "conversationId", conversationId,
                "chunk", chunk
        ));
    }

    private void sendToolCallStatus(String userId,
                                    String generationId,
                                    String conversationId,
                                    LlmProviderRouter.ToolCallDecision toolCall,
                                    String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "tool_call");
        payload.put("tool", toolCall.name());
        payload.put("toolCallId", toolCall.id());
        payload.put("status", status);
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("timestamp", System.currentTimeMillis());
        chatSessionRegistry.sendJsonToUser(userId, payload);
    }

    private void sendCompletionNotification(String userId,
                                            String generationId,
                                            String conversationId,
                                            boolean failed,
                                            boolean persistenceDegraded) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "completion");
        notification.put("generationId", generationId);
        notification.put("conversationId", conversationId);
        notification.put("status", failed ? "failed" : "finished");
        notification.put("message", failed ? "响应已中断" : "响应已完成");
        notification.put("timestamp", System.currentTimeMillis());
        notification.put("date", java.time.LocalDateTime.now().toString());
        if (!failed) {
            Map<Integer, ReferenceInfo> referenceMappings = generationReferenceMappings.get(generationId);
            if (referenceMappings != null && !referenceMappings.isEmpty()) {
                notification.put("referenceMappings", toSerializableReferenceMappings(referenceMappings));
            }
        }
        if (persistenceDegraded) {
            notification.put("persistenceDegraded", true);
            notification.put("persistenceWarning", "本次回复未能持久化到数据库，刷新后可能无法在历史中找到。");
        }
        chatSessionRegistry.sendJsonToUser(userId, notification);
    }

    private void handleError(String userId, String generationId, Throwable error) {
        logger.error("AI服务错误: {}", error.getMessage(), error);
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("type", "error");
        errorResponse.put("generationId", generationId);
        errorResponse.put("error", "AI服务暂时不可用，请稍后重试");
        chatSessionRegistry.sendJsonToUser(userId, errorResponse);
    }

    private void sendRateLimitMessage(String userId, String generationId, RateLimitExceededException exception) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "error");
        payload.put("generationId", generationId);
        payload.put("code", 429);
        payload.put("message", exception.getMessage());
        payload.put("retryAfterSeconds", exception.getRetryAfterSeconds());
        chatSessionRegistry.sendJsonToUser(userId, payload);
    }

    /**
     * 停止响应
     */
    public void stopResponse(String userId, String generationId) {
        String resolvedGenerationId = generationId;
        if (resolvedGenerationId == null || resolvedGenerationId.isBlank()) {
            resolvedGenerationId = chatGenerationStateService.getActiveGenerationForUser(userId)
                    .map(ChatGenerationStateService.GenerationSnapshot::generationId)
                    .orElse(null);
        }

        if (resolvedGenerationId == null || resolvedGenerationId.isBlank()) {
            logger.warn("收到停止请求但未找到活动生成任务，用户ID: {}", userId);
            return;
        }
        final String targetGenerationId = resolvedGenerationId;

        logger.info("收到停止请求，用户ID: {}，generationId: {}", userId, targetGenerationId);

        // 设置停止标志
        cancelledGenerations.add(targetGenerationId);
        stopFlags.put(targetGenerationId, true);
        chatGenerationStateService.markCancelled(targetGenerationId);
        LlmProviderRouter.StreamHandle streamHandle = activeStreams.get(targetGenerationId);
        if (streamHandle != null) {
            streamHandle.cancel();
        }
        CompletableFuture<String> responseFuture = responseFutures.get(targetGenerationId);
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(new CancellationException("响应已停止"));
        }

        chatSessionRegistry.sendJsonToUser(userId, Map.of(
                "type", "stop",
                "generationId", targetGenerationId,
                "message", "响应已停止",
                "timestamp", System.currentTimeMillis(),
                "date", java.time.Instant.now().toString()
        ));

        logger.info("已停止上游流式生成: generationId={}", targetGenerationId);
    }

    /**
     * 根据会话ID和引用编号获取文件MD5
     *
     * @param sessionId WebSocket会话ID
     * @param referenceNumber 引用编号
     * @return 文件MD5，如果找不到则返回null
     */
    public String getReferenceMd5(String generationId, int referenceNumber) {
        ReferenceInfo detail = getReferenceDetail(generationId, referenceNumber);
        return detail != null ? detail.fileMd5() : null;
    }

    public ReferenceInfo getReferenceDetail(String generationId, int referenceNumber) {
        logger.info("查询引用MD5: generationId={}, referenceNumber=#{}", generationId, referenceNumber);

        Map<Integer, ReferenceInfo> referenceMapping = generationReferenceMappings.get(generationId);
        if (referenceMapping == null) {
            referenceMapping = chatGenerationStateService.getGeneration(generationId)
                    .map(ChatGenerationStateService.GenerationSnapshot::referenceMappings)
                    .filter(mappings -> !mappings.isEmpty())
                    .map(this::toReferenceInfoMap)
                    .orElse(null);
        }
        if (referenceMapping == null) {
            logger.error("未找到生成任务 {} 的引用映射，当前所有生成任务: {}", generationId, generationReferenceMappings.keySet());
            return null;
        }

        logger.info("生成任务 {} 的引用映射内容: {}", generationId, referenceMapping);

        ReferenceInfo detail = referenceMapping.get(referenceNumber);
        if (detail == null) {
            logger.error("生成任务 {} 中未找到引用编号 #{}, 可用的引用编号: {}", generationId, referenceNumber, referenceMapping.keySet());
            return null;
        }

        logger.info("成功找到引用映射: generationId={}, referenceNumber=#{}, fileMd5={}, pageNumber={}",
                generationId, referenceNumber, detail.fileMd5(), detail.pageNumber());
        return detail;
    }

    private Map<Integer, ReferenceInfo> toReferenceInfoMap(Map<String, Map<String, Object>> serializedMappings) {
        Map<Integer, ReferenceInfo> referenceMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : serializedMappings.entrySet()) {
            Map<String, Object> item = entry.getValue();
            referenceMap.put(Integer.parseInt(entry.getKey()), new ReferenceInfo(
                    (String) item.get("fileMd5"),
                    (String) item.get("fileName"),
                    item.get("pageNumber") instanceof Number number ? number.intValue() : null,
                    (String) item.get("anchorText"),
                    (String) item.get("retrievalMode"),
                    (String) item.get("retrievalLabel"),
                    (String) item.get("retrievalQuery"),
                    (String) item.get("matchedChunkText"),
                    (String) item.get("evidenceSnippet"),
                    item.get("score") instanceof Number number ? number.doubleValue() : null,
                    item.get("chunkId") instanceof Number number ? number.intValue() : null
            ));
        }
        return referenceMap;
    }

    private ReferenceInfo buildReferenceInfo(SearchResult result, String fileLabel, String userMessage) {
        String matchedChunkText = trimToMaxLength(
                result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(),
                MAX_MATCHED_CHUNK_LEN
        );
        String evidenceSnippet = buildEvidenceSnippet(userMessage, result.getAnchorText(), matchedChunkText);

        return new ReferenceInfo(
                result.getFileMd5(),
                fileLabel,
                result.getPageNumber(),
                result.getAnchorText(),
                result.getRetrievalMode(),
                buildRetrievalLabel(result.getRetrievalMode()),
                normalizeEvidenceText(userMessage),
                matchedChunkText,
                evidenceSnippet,
                result.getScore(),
                result.getChunkId()
        );
    }

    private String buildRetrievalLabel(String retrievalMode) {
        if ("TEXT_ONLY".equalsIgnoreCase(retrievalMode)) {
            return "关键词召回";
        }
        return "混合召回（语义相关 + 关键词命中）";
    }

    private String buildEvidenceSnippet(String userMessage, String anchorText, String matchedChunkText) {
        String normalizedAnchorText = normalizeEvidenceText(anchorText);
        if (!normalizedAnchorText.isBlank()) {
            return trimToMaxLength(normalizedAnchorText, MAX_EVIDENCE_SNIPPET_LEN);
        }

        String normalizedMatchedChunk = normalizeEvidenceText(matchedChunkText);
        if (normalizedMatchedChunk.isBlank()) {
            String normalizedUserMessage = normalizeEvidenceText(userMessage);
            if (!normalizedUserMessage.isBlank()) {
                return trimToMaxLength(normalizedUserMessage, MAX_EVIDENCE_SNIPPET_LEN);
            }
            return "";
        }

        String[] sentences = normalizedMatchedChunk.split("(?<=[。！？!?；;])");
        for (String sentence : sentences) {
            String trimmedSentence = sentence.trim();
            if (trimmedSentence.length() >= 12) {
                return trimToMaxLength(trimmedSentence, MAX_EVIDENCE_SNIPPET_LEN);
            }
        }

        return trimToMaxLength(normalizedMatchedChunk, MAX_EVIDENCE_SNIPPET_LEN);
    }

    private String trimToMaxLength(String value, int maxLength) {
        String normalized = normalizeEvidenceText(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    private String normalizeEvidenceText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public record ReferenceInfo(
            String fileMd5,
            String fileName,
            Integer pageNumber,
            String anchorText,
            String retrievalMode,
            String retrievalLabel,
            String retrievalQuery,
            String matchedChunkText,
            String evidenceSnippet,
            Double score,
            Integer chunkId
    ) {
    }

}
