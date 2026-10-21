package com.smartrag.repository;

import com.smartrag.model.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {

    List<ConversationSession> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, ConversationSession.SessionStatus status);

    List<ConversationSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<ConversationSession> findByConversationId(String conversationId);

    boolean existsByConversationId(String conversationId);
}
