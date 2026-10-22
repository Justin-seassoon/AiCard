package com.aicard.docgen;

import com.aicard.docgen.model.ParsedSheet;
import com.aicard.docgen.model.ParsedWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 用 workBuddy spike 的 5 个「日本企业现实」样例验证解析。 */
class SpreadsheetParserTest {

    private final SpreadsheetParser parser = new SpreadsheetParser(new FileSanitizer());

    private ParsedWorkbook parse(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as(name).isNotNull();
            return parser.parse(in, name);
        }
    }

    @Test
    void mergedTwoLevelHeader() throws Exception {
        ParsedSheet s = parse("f1_merged_header.xlsx").sheets().get(0);
        assertThat(s.headers()).contains("商品カテゴリ", "月");
        assertThat(s.headers().get(2)).contains("売上金額").contains("数量");
        assertThat(s.rows()).hasSize(6);
        assertThat(s.rows().get(0)).contains("食品", "120", "2025年4月"); // 和暦→西暦
    }

    @Test
    void fullWidthNumbersNormalized() throws Exception {
        ParsedSheet s = parse("f2_fullwidth.xlsx").sheets().get(0);
        assertThat(s.headers()).contains("商品名", "売上（円）");
        assertThat(s.rows()).hasSize(4);
        assertThat(s.rows().get(0)).containsExactly("コーヒー", "1000");
    }

    @Test
    void multiSheet() throws Exception {
        ParsedWorkbook wb = parse("f3_multisheet.xlsx");
        assertThat(wb.sheets()).hasSize(2);
        assertThat(wb.sheets()).extracting(ParsedSheet::name).contains("商品マスタ", "売上データ");
    }

    @Test
    void footerNotesAndTotalsDetected() throws Exception {
        ParsedSheet s = parse("f4_footer_note.xlsx").sheets().get(0);
        assertThat(s.headers()).containsExactly("部署", "経費");
        assertThat(s.rows()).hasSize(3);
        assertThat(s.notes()).anyMatch(t -> t.contains("注"));
        assertThat(s.summaries()).anyMatch(t -> t.contains("合計"));
    }

    @Test
    void csvParsed() throws Exception {
        ParsedSheet s = parse("f5_clean.csv").sheets().get(0);
        assertThat(s.headers()).containsExactly("部署", "経費");
        assertThat(s.rows()).hasSize(3);
    }
}
