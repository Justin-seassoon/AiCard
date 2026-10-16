package com.aicard.agent.planner;

import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTask;
import com.aicard.agent.StepRecord;
import com.aicard.skb.provider.LLMProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** LLM 自主规划：由模型据目标/工具清单/历史选择下一步（{@link LLMProvider#planStep}）。 */
@Component
public class LlmPlanner implements AgentPlanner {

    private static final int OUTPUT_SNIPPET = 200;

    private final LLMProvider llm;

    public LlmPlanner(LLMProvider llm) {
        this.llm = llm;
    }

    @Override
    public String mode() {
        return AgentTask.MODE_LLM;
    }

    @Override
    public LLMProvider.PlanStep next(String goal, List<String> toolSpecs, List<StepRecord> history, AgentContext ctx) {
        List<String> hist = history.stream()
                .map(h -> h.tool() + " -> " + snippet(h.output()))
                .toList();
        return llm.planStep(goalLabel(goal), toolSpecs, hist);
    }

    private static String goalLabel(String goal) {
        return switch (goal) {
            case AgentTask.GOAL_ALERT_REPORT -> "業務アラートの報告書（docx）を作成する";
            case AgentTask.GOAL_USAGE_REPORT -> "利用レポート（xlsx）を作成する";
            default -> goal;
        };
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= OUTPUT_SNIPPET ? s : s.substring(0, OUTPUT_SNIPPET) + "…";
    }
}
