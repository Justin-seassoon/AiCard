package com.aicard.meeting;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 会议录音与智能纪要（P0 云端）：一次会议的元数据与处理状态。
 * <p>音频分片存共享卷，本实体只保存分片清单与转写/纪要结果；
 * <b>租户来自本行</b>（{@code customerId}/{@code storeId}）——异步处理时显式传入（C5）。</p>
 *
 * <p>状态流转：{@code recording → uploading → transcribing → drafting → draft → reviewed}；
 * 异常为 {@code failed}。分片缺失不改变状态，记录在 {@code missingSeqs}。</p>
 */
@Entity
@Table(name = "meeting")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Meeting {

    public static final String STATUS_RECORDING = "recording";
    public static final String STATUS_UPLOADING = "uploading";
    public static final String STATUS_TRANSCRIBING = "transcribing";
    public static final String STATUS_DRAFTING = "drafting";
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_REVIEWED = "reviewed";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false, unique = true, length = 64)
    private String meetingId;

    /** 端侧幂等键：同一设备重复 start 时复用已有会议。 */
    @Column(name = "client_key", length = 64)
    private String clientKey;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "staff_name", length = 64)
    private String staffName;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "chunk_sec", nullable = false)
    private Integer chunkSec;

    @Column(name = "codec", nullable = false, length = 16)
    private String codec;

    @Column(name = "sample_rate", nullable = false)
    private Integer sampleRate;

    /** 已收到的最大分片序号。 */
    @Column(name = "last_seq")
    private Integer lastSeq;

    /** meeting_end 声明的最后分片序号（完整性核对基准）。 */
    @Column(name = "expected_last_seq")
    private Integer expectedLastSeq;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_seqs", columnDefinition = "jsonb")
    private String missingSeqs;

    @Column(name = "transcript", columnDefinition = "text")
    private String transcript;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transcript_json", columnDefinition = "jsonb")
    private String transcriptJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "minutes_json", columnDefinition = "jsonb")
    private String minutesJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "audio_expires_at", nullable = false)
    private Instant audioExpiresAt;

    @Column(name = "audio_deleted_at")
    private Instant audioDeletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isReviewed() {
        return STATUS_REVIEWED.equals(status);
    }
}
