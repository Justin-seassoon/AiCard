package com.aicard.docgen.template;

import com.aicard.docgen.DocxRenderer;
import com.aicard.skb.provider.mock.MockLlmProvider;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateServiceTest {

    private final MockLlmProvider llm = new MockLlmProvider();
    private final TemplateService service =
            new TemplateService(llm, new DocxRenderer(), new InvoiceXlsxWriter());

    @Test
    void docxTemplateRendersVerbatimBody() throws Exception {
        llm.setDocument("来月の全社会議を開催します。\n11月10日 14:00 より本社会議室にて。");
        TemplateService.Generated g = service.generate("notice",
                Map.of("件名", "全社会議の開催", "宛先", "全社員", "要点", "11/10 14:00"));

        assertThat(g.mime()).isEqualTo(TemplateService.MIME_DOCX);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(g.bytes()))) {
            String text = doc.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
            assertThat(text).contains("全社会議の開催").contains("全社員").contains("11月10日").contains("AI が作成した下書き");
        }
    }

    @Test
    void xlsxTemplateComputesTotals() throws Exception {
        TemplateService.Generated g = service.generate("quote", Map.of(
                "宛先", "株式会社サンプル",
                "件名", "備品の見積",
                "items", List.of(
                        Map.of("name", "コピー用紙", "qty", 10, "unitPrice", 500),
                        Map.of("name", "ペン", "qty", 20, "unitPrice", 100))));

        assertThat(g.mime()).isEqualTo(TemplateService.MIME_XLSX);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(g.bytes()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(findValue(sheet, "小計")).isEqualTo("7000");
            assertThat(findValue(sheet, "消費税")).isEqualTo("700");
            assertThat(findValue(sheet, "合計")).isEqualTo("7700");
        }
    }

    @Test
    void xlsxTemplateRequiresItems() {
        assertThatThrownBy(() -> service.generate("invoice", Map.of("件名", "x")))
                .hasMessageContaining("明細");
    }

    private static String findValue(Sheet sheet, String key) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            var row = sheet.getRow(r);
            if (row != null && row.getCell(0) != null && key.equals(row.getCell(0).getStringCellValue())) {
                var v = row.getCell(1);
                return v == null ? null : (v.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                        ? String.valueOf((long) v.getNumericCellValue()) : v.getStringCellValue());
            }
        }
        return null;
    }
}
