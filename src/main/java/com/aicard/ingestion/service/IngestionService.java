package com.aicard.ingestion.service;

import com.aicard.ingestion.model.TranslationSample;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 旁路样本采集：翻译结果脱敏后异步落库。绝不阻塞翻译主链路，失败静默降级。
 * demo 默认只存脱敏文本，原始音频不采集。
 */
@Service
public class IngestionService {

    private final JdbcTemplate jdbc;

    public IngestionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void record(TranslationSample sample) {
        try {
            jdbc.update(
                    "INSERT INTO translation_sample(customer_id, store_id, session_id, turn_id, " +
                    "src_lang, tgt_lang, source_text, translated_text, lid_confidence, source_side) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    sample.customerId(), sample.storeId(), sample.sessionId(), sample.turnId(),
                    sample.srcLang(), sample.tgtLang(),
                    sanitize(sample.sourceText()), sanitize(sample.translatedText()),
                    sample.lidConfidence(), sample.sourceSide());
        } catch (Exception e) {
            // 旁路失败静默降级，不影响翻译主链路
        }
    }

    /** 基础脱敏：邮箱替换为 [EMAIL]，连续 4 位以上数字替换为 ####。 */
    String sanitize(String text) {
        if (text == null) {
            return null;
        }
        return text
                .replaceAll("[\\w.]+@[\\w.]+", "[EMAIL]")
                .replaceAll("\\d{4,}", "####");
    }
}
