package com.aicard.ota.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 一次固定配套发布：绑定 P4/C5 目标版本 + 普通/强制策略 + 说明。平台级，不绑租户。 */
@Entity
@Table(name = "ota_release")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtaRelease {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "update_id", nullable = false, unique = true, length = 64)
    private String updateId;

    @Column(name = "policy", nullable = false, length = 16)
    private String policy;

    @Column(name = "target_p4_version", nullable = false, length = 31)
    private String targetP4Version;

    @Column(name = "target_c5_version", nullable = false, length = 31)
    private String targetC5Version;

    @Column(name = "release_notes", length = 256)
    private String releaseNotes;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
