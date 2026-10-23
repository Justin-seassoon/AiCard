package com.aicard.meeting;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 会议音频分片清单；文件本体在共享卷，按 {@code storageKey} 定位。 */
@Entity
@Table(name = "meeting_chunk")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MeetingChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false, length = 64)
    private String meetingId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
