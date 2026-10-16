package com.aicard.agent;

import com.aicard.agent.planner.AgentPlanner;
import com.aicard.skb.provider.LLMProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p><b>C5（租户跨异步）</b>：租户从**任务行**取（{@code task.customerId/storeId}）→ 构造
 * {@link AgentContext} 显式传给工具——不依赖 {@code TenantContext}（异步线程里它是空的）。
 */
@Service
public class AgentOrchestrator {

    private static final int MAX_STEPS = 8;
    private static final int MAX_ATTEMPTS = 3; // 1 次 + 2 次重试

    private final AgentTaskRepository tasks;
    private final ToolRegistry tools;
    private final AgentValidator validator;
    private final ObjectMapper mapper;
    private final Map<String, AgentPlanner> planners;

    public AgentOrchestrator(AgentTaskRepository tasks, ToolRegistry tools, AgentValidator validator,
                             ObjectMapper mapper, List<AgentPlanner> planners) {
        this.tasks = tasks;
        this.tools = tools;
        this.validator = validator;
        this.mapper = mapper;
        this.planners = planners.stream().collect(Collectors.toMap(AgentPlanner::mode, p -> p));
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

        List<StepRecord> history = new ArrayList<>();
        try {
            AgentPlanner planner = planners.getOrDefault(task.getMode(), planners.get(AgentTask.MODE_RULE));
            for (int i = 0; i < MAX_STEPS; i++) {
                LLMProvider.PlanStep plan = planner.next(task.getGoal(), tools.specs(), history, ctx);
                if (plan == null || plan.done()) {
                    break;
                }
                AgentTool tool = tools.get(plan.tool());
                if (tool == null) {
                    fail(task, "未知工具：" + plan.tool());
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
                task.setStatus(AgentTask.STATUS_DONE);
                task.setResult("完了：" + history.size() + " ステップ");
            }
        } catch (Exception e) {
            fail(task, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        task.setSteps(toJson(history));
        touch(task);
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
