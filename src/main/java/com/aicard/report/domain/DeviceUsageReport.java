package com.aicard.report.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/** 设备用量周报/月报快照：按设备聚合翻译/问答指标，定时生成并落库。 */
@Entity
@Table(name = "device_usage_report")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeviceUsageReport {

    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "period_type", nullable = false, length = 8)
    private String periodType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "turn_count")
    private Integer turnCount;

    @Column(name = "session_count")
    private Integer sessionCount;

    @Column(name = "qa_hit_count")
    private Integer qaHitCount;

    @Column(name = "active_days")
    private Integer activeDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", columnDefinition = "jsonb")
    private String metrics;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;
}
