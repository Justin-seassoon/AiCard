package com.aicard.ingestion.model;

/** 问答旁路日志：SKB/VKB 问答问题/答案/命中状态（脱敏后落库）。 */
public record QaLog(
        Long customerId, Long storeId, String deviceId, String sessionId, String turnId,
        String source, String question, String answer, String status, String sourceRefs
) {}
