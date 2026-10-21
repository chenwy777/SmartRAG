package com.smartrag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionRegistry.class);

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ChatSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void registerSession(String userId, WebSocketSession session) {
        sessions.put(userId, session);
    }

    public void unregisterSession(String userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (key, current) -> current == session ? null : current);
    }

    public WebSocketSession getCurrentSession(String userId) {
        return sessions.get(userId);
    }

    public void sendJsonToUser(String userId, Map<String, ?> payload) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            logger.debug("用户 {} 当前没有可用的 WebSocket 会话，跳过发送", userId);
            return;
        }

        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            logger.error("向用户 {} 发送 WebSocket 消息失败: {}", userId, e.getMessage(), e);
        }
    }
}
