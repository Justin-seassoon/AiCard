package com.aicard.agent;

/**
 * 工具体系：agent 的「行动」层（行动 = 调用既有云端能力）。
 * 所有工具**接受显式租户上下文**（{@link AgentContext}）——这是 C5（租户跨异步）的落点。
 */
public final class AgentContext {
    private final Long customerId;
    private final Long storeId;

    public AgentContext(Long customerId, Long storeId) {
        this.customerId = customerId;
        this.storeId = storeId;
    }

    public Long customerId() { return customerId; }
    public Long storeId() { return storeId; }
}
