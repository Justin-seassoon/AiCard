package com.aicard.maintenance.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/** 点检任务：派发给设备的点检清单，执行并回填结果后闭环。 */
@Entity
@Table(name = "inspection_task")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InspectionTask {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_CLOSED = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_no", nullable = false, length = 64)
    private String taskNo;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checklist", nullable = false, columnDefinition = "jsonb")
    private String checklist;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "assignee", length = 64)
    private String assignee;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
