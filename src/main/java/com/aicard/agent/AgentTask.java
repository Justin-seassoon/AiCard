package com.aicard.agent;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * ② agent 执行：多步任务状态机（持久化）。
 * 状态流转：{@code pending → running → done | failed}；{@code steps} 逐步追加，进程崩溃后可查。
 * <b>租户来自本行</b>（{@code customerId}/{@code storeId}）——异步执行时据此重建上下文（C5）。
 */
@Entity
@Table(name = "agent_task")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentTask {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    public static final String MODE_RULE = "rule";
    public static final String MODE_LLM = "llm";
    public static final String MODE_AUTO = "auto";

    public static final String GOAL_ALERT_REPORT = "alert_report";
    public static final String GOAL_USAGE_REPORT = "usage_report";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "goal", nullable = false, columnDefinition = "text")
    private String goal;

    @Column(name = "mode", nullable = false, length = 8)
    private String mode;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps", columnDefinition = "jsonb")
    private String steps;

    @Column(name = "result", columnDefinition = "text")
    private String result;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "artifact_name", length = 128)
    private String artifactName;

    @Column(name = "artifact_mime", length = 96)
    private String artifactMime;

    @Column(name = "artifact_base64", columnDefinition = "text")
    private String artifactBase64;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
