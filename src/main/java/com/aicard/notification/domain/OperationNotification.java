package com.aicard.notification.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 运营通知/提醒：后台配置文案，定时推送到目标设备/群组。 */
@Entity
@Table(name = "operation_notification")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationNotification {

    public static final String STATUS_SCHEDULED = "scheduled";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String TARGET_GROUP = "group";
    public static final String TARGET_DEVICE = "device";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "source_language", nullable = false, length = 16)
    private String sourceLanguage;

    @Column(name = "target_type", nullable = false, length = 8)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;

    @Column(name = "trigger_type", nullable = false, length = 8)
    private String triggerType;

    @Column(name = "schedule_at")
    private Instant scheduleAt;

    @Column(name = "cron_expr", length = 64)
    private String cronExpr;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
