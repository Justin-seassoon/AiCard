package com.aicard.agent.planner;

import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTask;
import com.aicard.agent.StepRecord;
import com.aicard.skb.provider.LLMProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RulePlannerTest {

    private final RulePlanner planner = new RulePlanner(new ObjectMapper());
    private final AgentContext ctx = new AgentContext(1L, 1L);

    @Test
    void alertReportSequenceAndReconciledArgs() {
        LLMProvider.PlanStep s0 = planner.next(AgentTask.GOAL_ALERT_REPORT, List.of(), List.of(), ctx);
        assertThat(s0.tool()).isEqualTo("list_maintenance_orders");

        List<StepRecord> h1 = List.of(step("list_maintenance_orders", "{\"count\":2}"));
        assertThat(planner.next(AgentTask.GOAL_ALERT_REPORT, List.of(), h1, ctx).tool())
                .isEqualTo("list_inspection_tasks");

        List<StepRecord> h2 = List.of(h1.get(0), step("list_inspection_tasks", "{\"count\":5}"));
        LLMProvider.PlanStep s2 = planner.next(AgentTask.GOAL_ALERT_REPORT, List.of(), h2, ctx);
        assertThat(s2.tool()).isEqualTo("generate_report_docx");
        assertThat(s2.argsJson()).contains("\"openOrders\":2").contains("\"inspectionIssues\":5"); // 由前两步导出

        List<StepRecord> h3 = List.of(h2.get(0), h2.get(1), step("generate_report_docx", "{}"));
        assertThat(planner.next(AgentTask.GOAL_ALERT_REPORT, List.of(), h3, ctx).done()).isTrue();
    }

    @Test
    void usageReportSequence() {
        assertThat(planner.next(AgentTask.GOAL_USAGE_REPORT, List.of(), List.of(), ctx).tool())
                .isEqualTo("usage_summary");
        List<StepRecord> h1 = List.of(step("usage_summary", "{\"turnCount\":9}"));
        assertThat(planner.next(AgentTask.GOAL_USAGE_REPORT, List.of(), h1, ctx).tool())
                .isEqualTo("generate_usage_xlsx");
        List<StepRecord> h2 = List.of(h1.get(0), step("generate_usage_xlsx", "{}"));
        assertThat(planner.next(AgentTask.GOAL_USAGE_REPORT, List.of(), h2, ctx).done()).isTrue();
    }

    private static StepRecord step(String tool, String output) {
        return new StepRecord(tool, "{}", StepRecord.OK, output, null, 1, 1);
    }
}
