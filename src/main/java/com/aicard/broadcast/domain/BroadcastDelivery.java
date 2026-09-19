package com.aicard.broadcast.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 广播投递记录：一条广播到某台设备的译文 + 投递/回执状态。 */
@Entity
@Table(name = "broadcast_delivery")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BroadcastDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broadcast_id", nullable = false)
    private Long broadcastId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "translated_text", nullable = false, columnDefinition = "TEXT")
    private String translatedText;

    @Column(name = "target_language", nullable = false, length = 16)
    private String targetLanguage;

    /** 投递时间（null = 尚未投递，设备重连后补拉）。 */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** 回执时间（null = 未确认）。 */
    @Column(name = "ack_at")
    private Instant ackAt;
}
