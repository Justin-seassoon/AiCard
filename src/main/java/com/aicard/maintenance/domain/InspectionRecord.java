package com.aicard.maintenance.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 点检记录：某点检任务的执行结果。 */
@Entity
@Table(name = "inspection_record")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "results", nullable = false, columnDefinition = "jsonb")
    private String results;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "inspected_by", length = 64)
    private String inspectedBy;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
