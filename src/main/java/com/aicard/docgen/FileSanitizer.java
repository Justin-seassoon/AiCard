package com.aicard.docgen;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 文件内容**不可信数据**的清洗与注入检测。
 * 文件里的文本可能是提示注入（"ignore previous instructions" 等），绝不可当作指令传给 LLM；
 * 这里做去控制字符、长度截断与标记。
 */
@Component
public class FileSanitizer {

    private static final int MAX_CELL = 2000;

    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+)?previous|disregard\\s+(all\\s+)?previous|system\\s*prompt"
                    + "|システムプロンプト|指示を無視|前の指示を|you\\s+are\\s+now"
                    + "|<\\|im_start\\|>|<\\|im_end\\|>|\\[INST\\]|\\[/INST\\])");

    /** 去控制字符（保留换行/制表）并截断。 */
    public String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("[\\p{Cntrl}&&[^\\t\\n]]", "").trim();
        return s.length() <= MAX_CELL ? s : s.substring(0, MAX_CELL);
    }

    /** 是否疑似提示注入。 */
    public boolean suspicious(String text) {
        return text != null && INJECTION.matcher(text).find();
    }

    /** 中性化：加显式标记，避免被当作指令。 */
    public String neutralize(String text) {
        return "【要注意】" + text;
    }

    /** 清洗 + 注入检测一步到位。 */
    public Cell analyze(String raw) {
        String cleaned = clean(raw);
        boolean bad = suspicious(cleaned);
        return new Cell(bad ? neutralize(cleaned) : cleaned, bad);
    }

    public record Cell(String text, boolean suspicious) {}
}
