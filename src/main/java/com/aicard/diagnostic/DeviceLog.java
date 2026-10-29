package com.aicard.diagnostic;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 设备诊断日志索引：日志正文落共享卷，本表只存元数据与保留期。 */
@Entity
@Table(name = "device_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeviceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_id", nullable = false, unique = true, length = 64)
    private String logId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "meeting_id", length = 64)
    private String meetingId;

    @Column(name = "fw_version", length = 32)
    private String fwVersion;

    @Column(name = "level", nullable = false, length = 16)
    private String level;

    @Column(name = "captured_from")
    private Instant capturedFrom;

    @Column(name = "captured_to")
    private Instant capturedTo;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "raw_size_bytes", nullable = false)
    private Long rawSizeBytes;

    @Column(name = "compressed", nullable = false)
    private Boolean compressed;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
