package com.smartrag.controller;

import com.smartrag.handler.ChatWebSocketHandler;
import com.smartrag.service.AgentToolRegistry;
import com.smartrag.service.ChatGenerationStateService;
import com.smartrag.utils.JwtUtils;
import com.smartrag.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final JwtUtils jwtUtils;
    private final ChatGenerationStateService chatGenerationStateService;
    private final AgentToolRegistry agentToolRegistry;

    public ChatController(JwtUtils jwtUtils,
                          ChatGenerationStateService chatGenerationStateService,
                          AgentToolRegistry agentToolRegistry) {
        this.jwtUtils = jwtUtils;
        this.chatGenerationStateService = chatGenerationStateService;
        this.agentToolRegistry = agentToolRegistry;
    }
    
    /**
     * 获取WebSocket停止指令Token
     */
    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken(@RequestHeader("Authorization") String token) {
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
            }
            String jwtToken = token.replace("Bearer ", "");
            if (!jwtUtils.validateToken(jwtToken)) {
                return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
            }

            String cmdToken = ChatWebSocketHandler.getInternalCmdToken();
            
            // 检查token是否有效
            if (cmdToken == null || cmdToken.trim().isEmpty()) {
                return ResponseEntity.status(500).body(responseBody(500, "Token生成失败", null));
            }
            
            return ResponseEntity.ok(responseBody(200, "获取WebSocket停止指令Token成功", Map.of("cmdToken", cmdToken)));
            
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TOKEN", "system", "获取WebSocket Token失败", e);
            return ResponseEntity.status(500).body(responseBody(500, "服务器内部错误：" + e.getMessage(), null));
        }
    }

    @GetMapping("/generation/{generationId}")
    public ResponseEntity<?> getGeneration(
            @PathVariable String generationId,
            @RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取生成状态成功",
                chatGenerationStateService.getGenerationForUser(generationId, userId).orElse(null)
        ));
    }

    @GetMapping("/active-generation")
    public ResponseEntity<?> getActiveGeneration(@RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取当前活动生成状态成功",
                chatGenerationStateService.getActiveGenerationForUser(userId).orElse(null)
        ));
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> submitFeedback(@RequestHeader("Authorization") String token,
                                            @RequestBody FeedbackRequest request) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        if (request == null || request.rating() == null || request.rating().isBlank()) {
            return ResponseEntity.badRequest().body(responseBody(400, "rating 不能为空", null));
        }

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("rating", request.rating());
        String reason = buildFeedbackReason(request);
        if (!reason.isBlank()) {
            arguments.put("reason", reason);
        }

        AgentToolRegistry.ToolExecutionResult result =
                agentToolRegistry.executeTool("submit_feedback", arguments, userId);
        return ResponseEntity.ok(responseBody(200, "反馈已记录", result.data()));
    }

    private String buildFeedbackReason(FeedbackRequest request) {
        StringBuilder reason = new StringBuilder();
        if (request.reason() != null && !request.reason().isBlank()) {
            reason.append(request.reason().trim());
        }
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            appendReasonPart(reason, "conversationId=" + request.conversationId().trim());
        }
        if (request.generationId() != null && !request.generationId().isBlank()) {
            appendReasonPart(reason, "generationId=" + request.generationId().trim());
        }
        return reason.toString();
    }

    private void appendReasonPart(StringBuilder reason, String part) {
        if (!reason.isEmpty()) {
            reason.append("; ");
        }
        reason.append(part);
    }

    private String extractValidatedUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        String jwtToken = authorization.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            return null;
        }
        return jwtUtils.extractUserIdFromToken(jwtToken);
    }

    private Map<String, Object> responseBody(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        return response;
    }

    public record FeedbackRequest(
            String rating,
            String reason,
            String conversationId,
            String generationId
    ) {
    }
}
