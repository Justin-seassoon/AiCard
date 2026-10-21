package com.aicard.agent;

import com.aicard.agent.planner.AgentPlanner;
import com.aicard.skb.provider.LLMProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ② 主循环：{@code plan → act → observe → reflect(确定性校验) → 重试}，逐步落库。
 *
 * <p><b>C5（租户跨异步）</b>：租户从**任务行**取 → {@link AgentContext} 显式传给工具。
 * <p><b>护栏</b>：最大步数、任务总时限、未知工具 / 缺必填参数 → fail-stop 并给可读原因；
 * 一步都没规划出来 → 明确失败（不伪造成功）。
 */
@Service
public class AgentOrchestrator {

    private static final int MAX_STEPS = 8;
    private static final int MAX_ATTEMPTS = 3; // 1 次 + 2 次重试
    private static final int PLAN_ATTEMPTS = 3; // 规划调用重试（应对 LLM 网关瞬时错误）

    private final AgentTaskRepository tasks;
    private final ToolRegistry tools;
    private final AgentValidator validator;
    private final ResultComposer composer;
    private final ObjectMapper mapper;
    private final Map<String, AgentPlanner> planners;
    private final long maxDurationMs;

    public AgentOrchestrator(AgentTaskRepository tasks, ToolRegistry tools, AgentValidator validator,
                             ResultComposer composer, ObjectMapper mapper, List<AgentPlanner> planners,
                             @Value("${agent.max-duration-ms:120000}") long maxDurationMs) {
        this.tasks = tasks;
        this.tools = tools;
        this.validator = validator;
        this.composer = composer;
        this.mapper = mapper;
        this.planners = planners.stream().collect(Collectors.toMap(AgentPlanner::mode, p -> p));
        this.maxDurationMs = maxDurationMs;
    }

    /** 执行任务（幂等于 taskId；由 {@link AgentRunner} 异步调用）。 */
    public void run(Long taskId) {
        AgentTask task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        AgentContext ctx = new AgentContext(task.getCustomerId(), task.getStoreId()); // C5
        task.setStatus(AgentTask.STATUS_RUNNING);
        touch(task);

        long deadline = System.currentTimeMillis() + maxDurationMs;
        List<StepRecord> history = new ArrayList<>();
        try {
            AgentPlanner planner = planners.getOrDefault(task.getMode(), planners.get(AgentTask.MODE_AUTO));
            if (planner == null) {
                fail(task, "未知の実行モード：" + task.getMode());
            }
            for (int i = 0; planner != null && i < MAX_STEPS; i++) {
                if (System.currentTimeMillis() > deadline) {
                    fail(task, "タスクが時間上限（" + maxDurationMs + "ms）を超えました");
                    break;
                }

                LLMProvider.PlanStep plan = planWithRetry(planner, task, history, ctx);
                if (plan == null) {
                    break; // 规划失败已写入 task.error
                }
                if (plan.done()) {
                    break;
                }

                AgentTool tool = tools.get(plan.tool());
                if (tool == null) {
                    fail(task, "未知のツール：" + plan.tool());
                    break;
                }
                List<String> missing = AgentArgs.missing(AgentArgs.parse(plan.argsJson()), tool.requiredArgs());
                if (!missing.isEmpty()) {
                    fail(task, "ツール " + tool.name() + " の必須パラメータ不足：" + missing);
                    break;
                }

                Exec exec = attempt(tool, ctx, history, plan.argsJson());
                history.add(exec.record());
                task.setSteps(toJson(history));
                touch(task); // 逐步落库（可恢复）

                if (StepRecord.FAILED.equals(exec.record().status())) {
                    fail(task, exec.record().error());
                    break;
                }
                if (exec.result() != null && exec.result().artifact() != null) {
                    task.setArtifactName(exec.result().artifactName());
                    task.setArtifactMime(exec.result().artifactMime());
                    task.setArtifactBase64(Base64.getEncoder().encodeToString(exec.result().artifact()));
                }
            }

            if (AgentTask.STATUS_RUNNING.equals(task.getStatus())) {
                if (history.isEmpty()) {
                    fail(task, "実行可能な手順を計画できませんでした（目標を具体的にしてください）");
                } else if (AgentValidator.expectsArtifact(task.getGoal()) && task.getArtifactBase64() == null) {
                    fail(task, "成果物が作成されませんでした（この目標にはファイル出力が必要です）");
                } else {
                    task.setStatus(AgentTask.STATUS_DONE);
                    task.setResult(composer.compose(task.getGoal(), history, task.getArtifactName()));
                }
            }
        } catch (Exception e) {
            fail(task, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        task.setSteps(toJson(history));
        touch(task);
    }

    /** 规划调用重试：LLM 网关瞬时错误不应拖垮整任务。失败返回 null（已写入 task.error）。 */
    private LLMProvider.PlanStep planWithRetry(AgentPlanner planner, AgentTask task,
                                               List<StepRecord> history, AgentContext ctx) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= PLAN_ATTEMPTS; attempt++) {
            try {
                return planner.next(task.getGoal(), tools.specs(), history, ctx);
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        fail(task, "実行計画の作成に失敗しました（" + (last == null ? "unknown" : last.getMessage()) + "）");
        return null;
    }

    /** act → observe → reflect，失败重试（≤ {@link #MAX_ATTEMPTS}）。 */
    private Exec attempt(AgentTool tool, AgentContext ctx, List<StepRecord> history, String argsJson) {
        long t0 = System.currentTimeMillis();
        String lastError = null;
        ToolResult lastResult = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ToolResult result = tool.run(ctx, argsJson);
                String error = validator.validate(history, tool.name(), result);
                if (error == null) {
                    return new Exec(record(tool, argsJson, StepRecord.OK, result.output(), null, attempt, t0), result);
                }
                lastError = error;
                lastResult = result;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        return new Exec(record(tool, argsJson, StepRecord.FAILED,
                lastResult == null ? null : lastResult.output(), lastError, MAX_ATTEMPTS, t0), lastResult);
    }

    private static StepRecord record(AgentTool tool, String args, String status, String output, String error,
                                     int attempts, long t0) {
        return new StepRecord(tool.name(), args, status, output, error, attempts, System.currentTimeMillis() - t0);
    }

    private void fail(AgentTask task, String error) {
        task.setStatus(AgentTask.STATUS_FAILED);
        task.setError(error);
    }

    private void touch(AgentTask task) {
        task.setUpdatedAt(Instant.now());
        tasks.save(task);
    }

    private String toJson(List<StepRecord> history) {
        try {
            return mapper.writeValueAsString(history);
        } catch (Exception e) {
            return "[]";
        }
    }

    private record Exec(StepRecord record, ToolResult result) {}
}
