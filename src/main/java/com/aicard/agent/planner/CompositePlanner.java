package com.aicard.agent.planner;

import com.aicard.agent.AgentContext;
import com.aicard.agent.AgentTask;
import com.aicard.agent.StepRecord;
import com.aicard.skb.provider.LLMProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * auto 模式：**LLM 主规划**；仅当 LLM 调用失败（模型输出不可解析等）且目标命中已知意图时，
 * 回退到规则工作流（两个稳定模板）。LLM 正常输出时一律以 LLM 为准。
 */
@Component
public class CompositePlanner implements AgentPlanner {

    private final LlmPlanner llm;
    private final RulePlanner rule;

    public CompositePlanner(LlmPlanner llm, RulePlanner rule) {
        this.llm = llm;
        this.rule = rule;
    }

    @Override
    public String mode() {
        return AgentTask.MODE_AUTO;
    }

    @Override
    public LLMProvider.PlanStep next(String goal, List<String> toolSpecs, List<StepRecord> history, AgentContext ctx) {
        try {
            return llm.next(goal, toolSpecs, history, ctx);
        } catch (RuntimeException e) {
            String intent = matchIntent(goal);
            if (intent != null) {
                return rule.next(intent, toolSpecs, history, ctx);
            }
            throw e;
        }
    }

    /** 轻量意图匹配（仅用于 LLM 失败时的兜底）。 */
    static String matchIntent(String goal) {
        if (goal == null) {
            return null;
        }
        boolean report = (goal.contains("修繕") || goal.contains("点検") || goal.contains("アラート"))
                && (goal.contains("報告") || goal.contains("レポート") || goal.contains("まとめ"));
        boolean usage = (goal.contains("利用") || goal.contains("使用") || goal.contains("翻訳"))
                && (goal.contains("レポート") || goal.contains("報告") || goal.contains("まとめ"));
        if (report) {
            return AgentTask.GOAL_ALERT_REPORT;
        }
        if (usage) {
            return AgentTask.GOAL_USAGE_REPORT;
        }
        return null;
    }
}
