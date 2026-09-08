package com.aicard.ingestion.model;

/** 旁路采集的翻译样本（脱敏后落库，形成数据飞轮）。 */
public record TranslationSample(
        Long customerId, Long storeId, String sessionId, String turnId,
        String srcLang, String tgtLang, String sourceText, String translatedText,
        Double lidConfidence, String sourceSide
) {}
