package com.aicard.docgen.template;

import com.aicard.docgen.DocxRenderer;
import com.aicard.skb.provider.LLMProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 文书模板生成：**内容由 LLM 起草，排版由模板确定性渲染**。
 * 社内文书（通知/回覧/報告/議事録）→ docx；帳票（見積書/請求書）→ xlsx（明細来自结构化 fields）。
 */
@Service
public class TemplateService {

    public static final String MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String NOTE = "※ 本ファイルは AI が作成した下書きです。送信・提出前に担当者が確認してください。";

    private final LLMProvider llm;
    private final DocxRenderer docx;
    private final InvoiceXlsxWriter invoice;

    public TemplateService(LLMProvider llm, DocxRenderer docx, InvoiceXlsxWriter invoice) {
        this.llm = llm;
        this.docx = docx;
        this.invoice = invoice;
    }

    public record Generated(byte[] bytes, String filename, String mime) {}

    public Generated generate(String templateId, Map<String, Object> fields) {
        DocTemplate template = DocTemplate.byId(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知模板：" + templateId));
        Map<String, Object> f = fields == null ? Map.of() : fields;

        if (template.isSpreadsheet()) {
            List<Map<String, Object>> items = items(f.get("items"));
            if (items.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "帳票需要明細（items）");
            }
            byte[] bytes = invoice.write(template.label(), f, items);
            return new Generated(bytes, template.id() + ".xlsx", MIME_XLSX);
        }

        byte[] bytes = writeDocx(template, f);
        return new Generated(bytes, template.id() + ".docx", MIME_DOCX);
    }

    private byte[] writeDocx(DocTemplate template, Map<String, Object> f) {
        String subject = str(f, "件名", template.label());
        String to = str(f, "宛先", "");
        String from = str(f, "発信者", "");
        String points = str(f, "要点", "");

        String body = llm.draftDocument("文書の種類：" + template.label()
                + "\n件名：" + subject + "\n宛先：" + to + "\n要点：" + points
                + "\n※ 前文・本文・末文の構成で、" + template.label() + "の本文を作成してください。");

        List<String> lines = new ArrayList<>();
        if (!to.isBlank()) {
            lines.add("宛先：" + to);
        }
        if (!from.isBlank()) {
            lines.add("発信者：" + from);
        }
        lines.add("");
        lines.addAll(Arrays.asList((body == null ? "" : body).split("\n")));
        lines.add("");
        lines.add("以上");
        return docx.render(subject, lines, NOTE);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }
}
