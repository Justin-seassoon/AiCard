package com.aicard.agent;

/**
 * agent 工具：一个可被 agent 调用的云端能力。{@code args} 为 JSON 对象。
 */
public interface AgentTool {

    /** 工具名（唯一标识，供 LLM/规则规划引用）。 */
    String name();

    /** 一句描述（供 LLM 规划理解用途）。 */
    String description();

    /** 执行。租户经 {@code ctx} 显式传入（C5）。 */
    ToolResult run(AgentContext ctx, String argsJson);
}
