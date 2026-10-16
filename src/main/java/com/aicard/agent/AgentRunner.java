package com.aicard.agent;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * ② 异步执行入口。放在独立 bean，确保 {@code @Async} 代理生效。
 * 异步线程内**不再有** {@code TenantContext}——租户由 {@link AgentOrchestrator} 从任务行重建（C5）。
 */
@Component
public class AgentRunner {

    private final AgentOrchestrator orchestrator;

    public AgentRunner(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Async
    public void runAsync(Long taskId) {
        orchestrator.run(taskId);
    }
}
