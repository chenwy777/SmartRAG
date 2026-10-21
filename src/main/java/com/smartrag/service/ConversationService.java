package com.smartrag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrag.exception.CustomException;
import com.smartrag.model.Conversation;
import com.smartrag.model.ConversationSession;
import com.smartrag.model.User;
import com.smartrag.repository.ConversationRepository;
import com.smartrag.repository.ConversationSessionRepository;
import com.smartrag.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public void recordConversation(String username, String question, String answer) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        saveConversation(user, question, answer, null, null);
    }

    @Transactional
    public void recordConversation(Long userId, String question, String answer, String conversationId,
                                   Map<String, Map<String, Object>> referenceMappings) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        saveConversation(user, question, answer, conversationId, referenceMappings);
        updateSessionTitleIfDefault(conversationId, question);
        touchSessionUpdatedAt(conversationId);
    }

    private void saveConversation(User user, String question, String answer, String conversationId,
                                  Map<String, Map<String, Object>> referenceMappings) {
        Conversation conversation = new Conversation();
        conversation.setUser(user);
        conversation.setQuestion(question);
        conversation.setAnswer(answer);
        conversation.setConversationId(conversationId);
        conversation.setReferenceMappingsJson(writeReferenceMappings(referenceMappings));

        conversationRepository.save(conversation);
    }

    // ---- ConversationSession management ----

    public List<Map<String, Object>> getConversationSessions(Long userId) {
        List<ConversationSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ConversationSession session : sessions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", session.getId());
            item.put("conversationId", session.getConversationId());
            item.put("title", session.getTitle() != null ? session.getTitle() : "新对话");
            item.put("status", session.getStatus().name());
            item.put("createdAt", session.getCreatedAt() != null ? session.getCreatedAt().format(TIMESTAMP_FORMATTER) : null);
            item.put("updatedAt", session.getUpdatedAt() != null ? session.getUpdatedAt().format(TIMESTAMP_FORMATTER) : null);
            result.add(item);
        }

        return result;
    }

    public Map<String, Object> createConversationSession(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        String conversationId = UUID.randomUUID().toString();

        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(conversationId);
        session.setTitle("新对话");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);

        // Update Redis so the backend uses this new conversation for subsequent messages
        String redisKey = "user:" + userId + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("title", "新对话");
        result.put("status", "ACTIVE");
        result.put("createdAt", session.getCreatedAt().format(TIMESTAMP_FORMATTER));
        result.put("updatedAt", session.getUpdatedAt().format(TIMESTAMP_FORMATTER));
        return result;
    }

    public void ensureConversationSession(Long userId, String conversationId, String title) {
        if (sessionRepository.existsByConversationId(conversationId)) {
            return;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(conversationId);
        session.setTitle(title != null && !title.isBlank() ? title : "新对话");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);
    }

    public void switchCurrentConversation(Long userId, String conversationId) {
        if (!sessionRepository.existsByConversationId(conversationId)) {
            throw new CustomException("对话不存在", HttpStatus.NOT_FOUND);
        }
        String redisKey = "user:" + userId + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));
    }

    public void updateSessionTitle(String conversationId, String title) {
        sessionRepository.findByConversationId(conversationId).ifPresent(session -> {
            if (session.getTitle() == null || "新对话".equals(session.getTitle())) {
                session.setTitle(title);
                sessionRepository.save(session);
            }
        });
    }

    public void archiveConversationSession(String conversationId) {
        ConversationSession session = sessionRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        session.setStatus(ConversationSession.SessionStatus.ARCHIVED);
        sessionRepository.save(session);
    }

    public void unarchiveConversationSession(String conversationId) {
        ConversationSession session = sessionRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);
    }

    private void touchSessionUpdatedAt(String conversationId) {
        sessionRepository.findByConversationId(conversationId).ifPresent(session -> {
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    public void updateSessionTitleIfDefault(String conversationId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        String trimmed = title.length() > 50 ? title.substring(0, 50) : title;
        sessionRepository.findByConversationId(conversationId).ifPresent(session -> {
            if ("新对话".equals(session.getTitle())) {
                session.setTitle(trimmed);
                sessionRepository.save(session);
            }
        });
    }

    // ---- Message queries ----

    public List<Map<String, Object>> getMessagesByConversationId(String conversationId) {
        List<Conversation> conversations = conversationRepository.findByConversationIdOrderByTimestampAsc(conversationId);
        return toMessageHistory(conversations, false);
    }

    public List<Conversation> getConversations(String username, LocalDateTime startDate, LocalDateTime endDate) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        if (user.getRole() == User.Role.ADMIN && "all".equals(username)) {
            if (startDate != null && endDate != null) {
                return conversationRepository.findByTimestampBetweenOrderByTimestampAsc(startDate, endDate);
            } else {
                return conversationRepository.findAllByOrderByTimestampAsc();
            }
        } else {
            if (startDate != null && endDate != null) {
                return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc(
                        user.getId(), startDate, endDate);
            } else {
                return conversationRepository.findByUserIdOrderByTimestampAsc(user.getId());
            }
        }
    }

    public List<Conversation> getAllConversations(String adminUsername, String targetUsername,
                                                 LocalDateTime startDate, LocalDateTime endDate) {
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));

        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Unauthorized access", HttpStatus.FORBIDDEN);
        }

        if (targetUsername != null && !targetUsername.isEmpty()) {
            User targetUser = userRepository.findByUsername(targetUsername)
                    .orElseThrow(() -> new CustomException("Target user not found", HttpStatus.NOT_FOUND));

            if (startDate != null && endDate != null) {
                return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc(
                        targetUser.getId(), startDate, endDate);
            } else {
                return conversationRepository.findByUserIdOrderByTimestampAsc(targetUser.getId());
            }
        } else {
            if (startDate != null && endDate != null) {
                return conversationRepository.findByTimestampBetweenOrderByTimestampAsc(startDate, endDate);
            } else {
                return conversationRepository.findAllByOrderByTimestampAsc();
            }
        }
    }

    public List<Map<String, Object>> toMessageHistory(List<Conversation> conversations, boolean includeUsername) {
        List<Map<String, Object>> messages = new ArrayList<>();

        conversations.stream()
                .sorted(Comparator
                        .comparing(Conversation::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Conversation::getId))
                .forEach(conversation -> {
                    String timestamp = conversation.getTimestamp() != null
                            ? conversation.getTimestamp().format(TIMESTAMP_FORMATTER)
                            : null;
                    String messageConversationId = conversation.getConversationId() != null
                            ? conversation.getConversationId()
                            : String.valueOf(conversation.getId());

                    messages.add(buildMessage(
                            "user",
                            conversation.getQuestion(),
                            timestamp,
                            messageConversationId,
                            null,
                            includeUsername ? conversation.getUser().getUsername() : null
                    ));
                    messages.add(buildMessage(
                            "assistant",
                            conversation.getAnswer(),
                            timestamp,
                            messageConversationId,
                            parseReferenceMappings(conversation.getReferenceMappingsJson()),
                            includeUsername ? conversation.getUser().getUsername() : null
                    ));
                });

        return messages;
    }

    private Map<String, Object> buildMessage(String role, String content, String timestamp, String conversationId,
                                             Map<String, Map<String, Object>> referenceMappings, String username) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);
        if (timestamp != null) {
            message.put("timestamp", timestamp);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            message.put("conversationId", conversationId);
        }
        if (referenceMappings != null && !referenceMappings.isEmpty()) {
            message.put("referenceMappings", referenceMappings);
        }
        if (username != null && !username.isBlank()) {
            message.put("username", username);
        }
        return message;
    }

    private String writeReferenceMappings(Map<String, Map<String, Object>> referenceMappings) {
        if (referenceMappings == null || referenceMappings.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(referenceMappings);
        } catch (Exception e) {
            logger.warn("序列化引用映射失败，将跳过持久化引用详情", e);
            return null;
        }
    }

    private Map<String, Map<String, Object>> parseReferenceMappings(String referenceMappingsJson) {
        if (referenceMappingsJson == null || referenceMappingsJson.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(referenceMappingsJson, new TypeReference<Map<String, Map<String, Object>>>() {});
        } catch (Exception e) {
            logger.warn("解析引用映射失败，将返回无引用详情的历史记录", e);
            return null;
        }
    }
}
