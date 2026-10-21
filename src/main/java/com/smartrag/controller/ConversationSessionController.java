package com.smartrag.controller;

import com.smartrag.exception.CustomException;
import com.smartrag.service.ConversationService;
import com.smartrag.utils.JwtUtils;
import com.smartrag.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/conversations")
public class ConversationSessionController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ConversationService conversationService;

    @GetMapping
    public ResponseEntity<?> listSessions(@RequestHeader("Authorization") String token) {
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            Long userId = Long.parseLong(jwtUtils.extractUserIdFromToken(token.replace("Bearer ", "")));
            List<Map<String, Object>> sessions = conversationService.getConversationSessions(userId);

            return ResponseEntity.ok(Map.of("code", 200, "message", "获取对话列表成功", "data", sessions));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("LIST_SESSIONS", username, "获取对话列表异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createSession(@RequestHeader("Authorization") String token) {
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            Long userId = Long.parseLong(jwtUtils.extractUserIdFromToken(token.replace("Bearer ", "")));
            Map<String, Object> session = conversationService.createConversationSession(userId);

            return ResponseEntity.ok(Map.of("code", 200, "message", "创建新对话成功", "data", session));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("CREATE_SESSION", username, "创建对话异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    @PutMapping("/{conversationId}/archive")
    public ResponseEntity<?> archiveSession(
            @RequestHeader("Authorization") String token,
            @PathVariable String conversationId) {
        try {
            String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            conversationService.archiveConversationSession(conversationId);
            return ResponseEntity.ok(Map.of("code", 200, "message", "归档成功"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    @PutMapping("/{conversationId}/switch")
    public ResponseEntity<?> switchSession(
            @RequestHeader("Authorization") String token,
            @PathVariable String conversationId) {
        try {
            String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            Long userId = Long.parseLong(jwtUtils.extractUserIdFromToken(token.replace("Bearer ", "")));
            conversationService.switchCurrentConversation(userId, conversationId);
            return ResponseEntity.ok(Map.of("code", 200, "message", "切换对话成功"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    @PutMapping("/{conversationId}/unarchive")
    public ResponseEntity<?> unarchiveSession(
            @RequestHeader("Authorization") String token,
            @PathVariable String conversationId) {
        try {
            String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            conversationService.unarchiveConversationSession(conversationId);
            return ResponseEntity.ok(Map.of("code", 200, "message", "取消归档成功"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }
}
