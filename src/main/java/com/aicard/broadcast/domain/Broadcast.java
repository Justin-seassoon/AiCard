package com.aicard.broadcast.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 广播：管理员向某群发送的一条源消息。 */
@Entity
@Table(name = "broadcast")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Broadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "source_text", nullable = false, columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "source_language", nullable = false, length = 16)
    private String sourceLanguage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
