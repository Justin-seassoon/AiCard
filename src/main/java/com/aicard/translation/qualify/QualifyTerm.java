package com.aicard.translation.qualify;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 质量门控词表条目：品牌词（brand）或短词白名单（whitelist），按租户隔离。 */
@Entity
@Table(name = "qualify_term")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QualifyTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "type", nullable = false, length = 16)
    private String type;  // whitelist | brand

    @Column(name = "term", nullable = false, length = 128)
    private String term;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
