package com.aicard.agent.planner;

import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTask;
import com.aicard.agent.StepRecord;
import com.aicard.skb.provider.LLMProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则规划：按预定义工作流推进（goal → 固定工具序列）。
 * **确定性、稳定**——不依赖模型 tool-calling；LLM 模式见 {@link LlmPlanner}。
 */
@Component
public class RulePlanner implements AgentPlanner {

    private final ObjectMapper mapper;

    public RulePlanner(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String mode() {
        return AgentTask.MODE_RULE;
    }

    @Override
    public LLMProvider.PlanStep next(String goal, List<String> toolSpecs, List<StepRecord> history, AgentContext ctx) {
        if (AgentTask.GOAL_ALERT_REPORT.equals(goal)) {
            return switch (history.size()) {
                case 0 -> LLMProvider.PlanStep.tool("list_maintenance_orders", "{\"status\":\"open\"}");
                case 1 -> LLMProvider.PlanStep.tool("list_inspection_tasks", "{}");
                case 2 -> LLMProvider.PlanStep.tool("generate_report_docx", reportArgs(history));
                default -> LLMProvider.PlanStep.finished();
            };
        }
        if (AgentTask.GOAL_USAGE_REPORT.equals(goal)) {
            return switch (history.size()) {
                case 0 -> LLMProvider.PlanStep.tool("usage_summary", "{\"days\":7}");
                case 1 -> LLMProvider.PlanStep.tool("generate_usage_xlsx", "{\"days\":7}");
                default -> LLMProvider.PlanStep.finished();
            };
        }
        return LLMProvider.PlanStep.finished();
    }

    /** 报告步骤的参数由前两步的输出（count）推导——供 reflect 跨步对账。 */
    private String reportArgs(List<StepRecord> history) {
        int orders = count(history.get(0).output());
        int inspections = count(history.get(1).output());
        return "{\"openOrders\":" + orders + ",\"inspectionIssues\":" + inspections + "}";
    }

    private int count(String outputJson) {
        try {
            return mapper.readTree(outputJson).path("count").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }
}
