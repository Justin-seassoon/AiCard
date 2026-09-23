package com.aicard.ota.domain;

import jakarta.persistence.*;
import lombok.*;

/** 一次发布内的单个固件包（P4 或 C5），含执行顺序与下载所需元数据。 */
@Entity
@Table(name = "ota_package")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtaPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(name = "module", nullable = false, length = 4)
    private String module;

    @Column(name = "target_version", nullable = false, length = 31)
    private String targetVersion;

    @Column(name = "image_size", nullable = false)
    private Long imageSize;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
