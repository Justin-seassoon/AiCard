package com.aicard.broadcast.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 广播群组：一组设备的集合（工地工作群 / 酒店班次群…），纳入四层租户隔离。 */
@Entity
@Table(name = "device_group")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeviceGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
