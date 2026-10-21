package com.aicard.docgen;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleXlsxWriterTest {

    @Test
    void writesHeaderAndRows() throws Exception {
        byte[] bytes = new SimpleXlsxWriter().write("表",
                List.of("項目", "値"),
                List.of(List.of("A", "1"), List.of("B", "2")));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("項目");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("1");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("B");
        }
    }

    @Test
    void sanitizesSheetName() throws Exception {
        byte[] bytes = new SimpleXlsxWriter().write("a/b:c*?[]", List.of("x"), List.of(List.of("1")));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheetName(0)).doesNotContain("/", ":", "*", "?", "[", "]");
        }
    }
}
