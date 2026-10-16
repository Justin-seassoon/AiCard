package com.aicard.agent.planner;

import com.aicard.agent.AgentContext;
import com.aicard.agent.StepRecord;
import com.aicard.skb.provider.LLMProvider;

import java.util.List;

/** 规划器：决定下一步调用哪个工具。{@code mode()} 与 {@code AgentTask.mode} 对应。 */
public interface AgentPlanner {

    String mode();

    LLMProvider.PlanStep next(String goal, List<String> toolSpecs, List<StepRecord> history, AgentContext ctx);
}
