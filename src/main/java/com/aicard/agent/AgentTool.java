package com.aicard.agent;

import java.util.List;

/**
 * agent 工具：一个可被 agent 调用的云端能力。{@code args} 为 JSON 对象。
 *
 * <p>{@link #argsHint()} 与 {@link #requiredArgs()} 供 LLM 规划与执行前校验使用——
 * 让模型知道参数形状，让编排器在缺参时**不执行**并给出可读原因。
 */
public interface AgentTool {

    /** 工具名（唯一标识，供 LLM/规则规划引用）。 */
    String name();

    /** 一句描述（供 LLM 规划理解用途）。 */
    String description();

    /** 参数提示（JSON 片段，给 LLM 参考），默认无参。 */
    default String argsHint() {
        return "{}";
    }

    /** 必填参数名（执行前校验），默认无。 */
    default List<String> requiredArgs() {
        return List.of();
    }

    /** 执行。租户经 {@code ctx} 显式传入（C5）。 */
    ToolResult run(AgentContext ctx, String argsJson);
}
