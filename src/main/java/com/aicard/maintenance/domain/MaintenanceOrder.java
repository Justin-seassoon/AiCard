package com.aicard.maintenance.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 维修单：门店设备维修上报与处理闭环（纯后台录入）。 */
@Entity
@Table(name = "maintenance_order")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MaintenanceOrder {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reporter", length = 64)
    private String reporter;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
