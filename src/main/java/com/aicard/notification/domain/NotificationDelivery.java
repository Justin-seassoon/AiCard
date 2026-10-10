package com.aicard.notification.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 通知投递记录：某通知投递到某设备的送达状态（delivered_at 为 null 表示待补拉）。 */
@Entity
@Table(name = "notification_delivery")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "translated_text", columnDefinition = "TEXT")
    private String translatedText;

    @Column(name = "target_language", length = 16)
    private String targetLanguage;

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
