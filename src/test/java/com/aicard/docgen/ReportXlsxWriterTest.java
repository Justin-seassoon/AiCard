package com.aicard.docgen;

import com.aicard.report.repository.DeviceAggregate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportXlsxWriterTest {

    private final ReportXlsxWriter writer = new ReportXlsxWriter(new ObjectMapper());

    @Test
    void writesMetricsAndLanguageBreakdown() throws Exception {
        DeviceAggregate agg = new DeviceAggregate(12, 3, 4, 2, "{\"langs\":{\"en\":7,\"zh\":5}}");

        byte[] bytes = writer.write("利用レポート", "店舗全体", "2026-10-01 ～ 2026-10-08", agg);
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(cellValue(sheet, "翻訳ターン数")).isEqualTo("12");
            assertThat(cellValue(sheet, "セッション数")).isEqualTo("3");
            assertThat(cellValue(sheet, "QAヒット数")).isEqualTo("4");
            assertThat(cellValue(sheet, "対象")).isEqualTo("店舗全体");
            assertThat(cellValue(sheet, "en")).isEqualTo("7");
            assertThat(cellValue(sheet, "zh")).isEqualTo("5");
        }
    }

    /** 扫描 A 列找到 key，返回同行 B 列的值。 */
    private static String cellValue(Sheet sheet, String key) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            var row = sheet.getRow(r);
            if (row != null && row.getCell(0) != null && key.equals(row.getCell(0).getStringCellValue())) {
                return row.getCell(1).getStringCellValue();
            }
        }
        return null;
    }
}
