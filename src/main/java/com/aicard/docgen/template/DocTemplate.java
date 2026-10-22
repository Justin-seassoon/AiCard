package com.aicard.docgen.template;

import java.util.Arrays;
import java.util.Optional;

/** 文书模板目录（③ B）。{@code format} 决定产物是 docx 还是 xlsx。 */
public enum DocTemplate {

    NOTICE("notice", "通知", "docx", "社内通知の下書き"),
    CIRCULAR("circular", "回覧", "docx", "回覧文の下書き"),
    REPORT("business_report", "報告", "docx", "業務報告書の下書き"),
    MINUTES("minutes", "議事録", "docx", "会議議事録の下書き"),
    QUOTE("quote", "見積書", "xlsx", "見積書（明細から生成）"),
    INVOICE("invoice", "請求書", "xlsx", "請求書（明細から生成）");

    private final String id;
    private final String label;
    private final String format;
    private final String description;

    DocTemplate(String id, String label, String format, String description) {
        this.id = id;
        this.label = label;
        this.format = format;
        this.description = description;
    }

    public String id() { return id; }
    public String label() { return label; }
    public String format() { return format; }
    public String description() { return description; }
    public boolean isSpreadsheet() { return "xlsx".equals(format); }

    public static Optional<DocTemplate> byId(String id) {
        return Arrays.stream(values()).filter(t -> t.id.equals(id)).findFirst();
    }
}
