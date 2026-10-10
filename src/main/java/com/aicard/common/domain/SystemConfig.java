package com.aicard.common.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 后台可配配置项：支持全局 / 租户 / 门店三级覆盖（customer_id、store_id 为 NULL 表示不限定）。 */
@Entity
@Table(name = "system_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 255)
    private String configValue;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
