package com.aicard.translation.qualify;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 质量门控分类器（纯函数，无副作用）：输入游客发言的转录文本 + 语音时长，
 * 输出质量分类。过滤 short / numeric / brand / whitelist 四类无效语音，
 * 避免它们污染 visitor_language 记忆、造成软锁定漂移。见架构 spec §7.4 ②/④。
 *
 * 判定顺序（短路返回）：SHORT（时长或空文本）→ NUMERIC → BRAND → WHITELIST → NORMAL。
 */
public class UtteranceQualifier {

    /** 纯数字/符号（含至少一个数字）：数字、空白与常见符号 . , % + - ¥ $ € £。 */
    private static final Pattern NUMERIC = Pattern.compile("^[\\d\\s.,%+\\-¥$€£]*\\d[\\d\\s.,%+\\-¥$€£]*$");

    private final long minDurationMs;      // <0 关闭 short 门控
    private final boolean numericFilter;
    private final Set<String> whitelist;   // 小写归一化，精确匹配
    private final Set<String> brandTerms;  // 小写归一化，包含匹配

    public UtteranceQualifier(long minDurationMs, boolean numericFilter,
                              Set<String> whitelist, Set<String> brandTerms) {
        this.minDurationMs = minDurationMs;
        this.numericFilter = numericFilter;
        this.whitelist = whitelist;
        this.brandTerms = brandTerms;
    }

    public UtteranceQuality qualify(String text, Long durationMs) {
        if (minDurationMs > 0 && durationMs != null && durationMs < minDurationMs) {
            return UtteranceQuality.SHORT;
        }
        if (text == null || text.isBlank()) {
            return UtteranceQuality.SHORT;
        }
        String trimmed = text.trim();
        if (numericFilter && NUMERIC.matcher(trimmed).matches()) {
            return UtteranceQuality.NUMERIC;
        }
        String lower = trimmed.toLowerCase();
        if (!brandTerms.isEmpty() && brandTerms.stream().anyMatch(lower::contains)) {
            return UtteranceQuality.BRAND;
        }
        if (whitelist.contains(lower)) {
            return UtteranceQuality.WHITELIST;
        }
        return UtteranceQuality.NORMAL;
    }
}
