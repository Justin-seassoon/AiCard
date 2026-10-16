package com.aicard.docgen;

import com.aicard.skb.provider.mock.MockLlmProvider;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDocWriterTest {

    @Test
    void writesDraftWithConfirmationNote() throws Exception {
        MockLlmProvider llm = new MockLlmProvider();
        llm.setDocument("来月の全社会議を開催します。\n※要確認：会場");
        BusinessDocWriter writer = new BusinessDocWriter(llm, new DocxRenderer());

        byte[] bytes = writer.write("通知", "全社会議の開催について", "日時 11/10 14:00");
        assertThat(bytes).isNotEmpty();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
            assertThat(text).contains("全社会議の開催について");   // 標題
            assertThat(text).contains("来月の全社会議を開催します");  // 正文（LLM 草稿）
            assertThat(text).contains("AI が作成した下書き");        // 要確認标注
        }
    }
}
