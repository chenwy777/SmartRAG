package com.smartrag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversations", indexes = {
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_timestamp", columnList = "timestamp"),
        @Index(name = "idx_conversation_id", columnList = "conversation_id")
})
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 对话记录唯一标识

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // 关联用户

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question; // 用户提问内容

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer; // 系统回答内容

    @Column(name = "conversation_id", length = 64)
    private String conversationId; // 逻辑会话ID，用于历史记录关联

    @Column(name = "reference_mappings_json", columnDefinition = "LONGTEXT")
    private String referenceMappingsJson; // 助手回复对应的引用映射

    @CreationTimestamp
    private LocalDateTime timestamp; // 对话时间戳
}
