package com.smartrag.controller;

import com.smartrag.exception.CustomException;
import com.smartrag.service.ConversationService;
import com.smartrag.utils.JwtUtils;
import com.smartrag.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/conversation")
public class ConversationController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ConversationService conversationService;

    @GetMapping
    public ResponseEntity<?> getConversations(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date,
            @RequestParam(required = false) String conversationId) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_CONVERSATIONS");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_CONVERSATIONS", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取对话历史失败：无效token");
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            LogUtils.logBusiness("GET_CONVERSATIONS", username, "开始查询用户持久化对话历史");

            List<Map<String, Object>> messages;

            if (conversationId != null && !conversationId.isBlank()) {
                messages = conversationService.getMessagesByConversationId(conversationId);
            } else {
                LocalDateTime startDateTime = parseStartDate(start_date);
                LocalDateTime endDateTime = parseEndDate(end_date);
                messages = conversationService.toMessageHistory(
                        conversationService.getConversations(username, startDateTime, endDateTime),
                        false
                );
            }

            LogUtils.logBusiness("GET_CONVERSATIONS", username, "持久化历史查询完成，共 %d 条消息", messages.size());
            LogUtils.logUserOperation(username, "GET_CONVERSATIONS", "conversation_history", "SUCCESS");
            monitor.end("获取对话历史成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取对话历史成功");
            response.put("data", messages);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "获取对话历史失败: %s", e, e.getMessage());
            monitor.end("获取对话历史失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "获取对话历史异常: %s", e, e.getMessage());
            monitor.end("获取对话历史异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    private LocalDateTime parseStartDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":00");
                }

                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":00:00");
                }

                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).atStartOfDay();
                }
            } catch (Exception e2) {
                LogUtils.logBusinessError("PARSE_START_DATETIME", "system", "无法解析起始时间: %s", e2, dateTimeStr);
                throw new CustomException("无效的起始时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }

        throw new CustomException("无效的起始时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }

    private LocalDateTime parseEndDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":59");
                }

                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":59:59");
                }

                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).plusDays(1).atStartOfDay().minusSeconds(1);
                }
            } catch (Exception e2) {
                LogUtils.logBusinessError("PARSE_END_DATETIME", "system", "无法解析结束时间: %s", e2, dateTimeStr);
                throw new CustomException("无效的结束时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }

        throw new CustomException("无效的结束时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }
}
