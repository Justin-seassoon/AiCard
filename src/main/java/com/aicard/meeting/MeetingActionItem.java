package com.aicard.meeting;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 会议待办事项（结构化落库）：周报「真实任务进度」的数据源。
 * 负责人/期限不明确时保持 null，界面显示「要確認」——不自行编造。
 */
@Entity
@Table(name = "meeting_action_item")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MeetingActionItem {

    public static final String STATUS_TODO = "todo";
    public static final String STATUS_DOING = "doing";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false, length = 64)
    private String meetingId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "task", nullable = false, columnDefinition = "text")
    private String task;

    @Column(name = "owner", length = 128)
    private String owner;

    @Column(name = "due_date", length = 64)
    private String dueDate;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 根拠：转写时间戳，如 {@code 00:42:15}。 */
    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
