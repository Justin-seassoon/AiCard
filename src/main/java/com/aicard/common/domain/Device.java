package com.aicard.common.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "device")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Device {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true, length = 64)
    private String deviceId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "staff_language", length = 16)
    private String staffLanguage;

    @Column(name = "product_model", length = 32)
    private String productModel;

    @Column(name = "hardware_version", length = 32)
    private String hardwareVersion;

    @Column(name = "last_p4_version", length = 31)
    private String lastP4Version;

    @Column(name = "last_c5_version", length = 31)
    private String lastC5Version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
