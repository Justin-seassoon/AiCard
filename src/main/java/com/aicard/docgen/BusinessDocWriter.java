package com.aicard.docgen;

import com.aicard.skb.provider.LLMProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * ③ 文档生成 · docx：LLM 起草日语社内文书（通知 / 回覧 / 報告）→ Word 文件。
 * 产物是**草稿**，正文末附「AI 下書き・要確認」标注（与产品红线一致）。渲染委托 {@link DocxRenderer}。
 */
@Component
public class BusinessDocWriter {

    private static final String NOTE = "※ 本ファイルは AI が作成した下書きです。送信・提出前に担当者が確認してください。";

    private final LLMProvider llm;
    private final DocxRenderer renderer;

    public BusinessDocWriter(LLMProvider llm, DocxRenderer renderer) {
        this.llm = llm;
        this.renderer = renderer;
    }

    public byte[] write(String docType, String topic, String points) {
        String body = llm.draftDocument(instruction(docType, topic, points));
        List<String> lines = Arrays.asList((body == null ? "" : body).split("\n"));
        String title = (docType == null || docType.isBlank() ? "社内文書" : docType) + "：" + (topic == null ? "" : topic);
        return renderer.render(title, lines, NOTE);
    }

    private static String instruction(String docType, String topic, String points) {
        return "文書の種類：" + docType + "\n主題：" + topic + "\n要点：" + (points == null ? "" : points);
    }
}
