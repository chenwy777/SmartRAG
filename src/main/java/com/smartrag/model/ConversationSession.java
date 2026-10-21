package com.smartrag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_sessions", indexes = {
        @Index(name = "idx_cs_user_id", columnList = "user_id"),
        @Index(name = "idx_cs_conversation_id", columnList = "conversation_id", unique = true),
        @Index(name = "idx_cs_status", columnList = "status")
})
public class ConversationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "conversation_id", length = 64, nullable = false, unique = true)
    private String conversationId;

    @Column(length = 255)
    private String title;

    @Column(length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private SessionStatus status = SessionStatus.ACTIVE;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum SessionStatus {
        ACTIVE, ARCHIVED
    }
}
