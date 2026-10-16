package com.aicard.agent;

/** 单步记录（进入状态机并持久化到 {@code agent_task.steps}）。 */
public record StepRecord(String tool, String args, String status, String output, String error,
                         int attempts, long ms) {

    public static final String OK = "ok";
    public static final String FAILED = "failed";
}
