package com.smartrag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrag.model.Conversation;
import com.smartrag.model.User;
import com.smartrag.repository.ConversationRepository;
import com.smartrag.repository.ConversationSessionRepository;
import com.smartrag.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ConversationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationSessionRepository sessionRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testRecordConversation() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        conversationService.recordConversation("testuser", "What is AI?", "AI stands for Artificial Intelligence.");

        verify(conversationRepository, times(1)).save(any(Conversation.class));
    }

    @Test
    void testGetConversations() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        Conversation conversation = new Conversation();
        conversation.setId(1L);
        conversation.setQuestion("What is AI?");
        conversation.setAnswer("AI stands for Artificial Intelligence.");
        when(conversationRepository.findByUserIdOrderByTimestampAsc(anyLong())).thenReturn(List.of(conversation));

        var result = conversationService.getConversations("testuser", null, null);
        assertEquals(1, result.size());
    }
}