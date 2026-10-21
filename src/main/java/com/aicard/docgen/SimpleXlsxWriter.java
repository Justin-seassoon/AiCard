package com.aicard.docgen;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** 通用 xlsx 生成：表头 + 数据行（确定性，无 LLM）。供 agent 的 write_xlsx 工具使用。 */
@Component
public class SimpleXlsxWriter {

    public byte[] write(String title, List<String> columns, List<List<String>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName(title));

            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);

            Row head = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(columns.get(c) == null ? "" : columns.get(c));
                cell.setCellStyle(header);
            }

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> vals = rows.get(r);
                for (int c = 0; c < vals.size(); c++) {
                    row.createCell(c).setCellValue(vals.get(c) == null ? "" : vals.get(c));
                }
            }

            for (int c = 0; c < columns.size(); c++) {
                sheet.setColumnWidth(c, 20 * 256);
            }

            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("xlsx 生成失败", e);
        }
    }

    /** Excel sheet 名限制：≤31 字符、不含 \/:*?[]。 */
    private static String safeSheetName(String title) {
        String name = (title == null ? "" : title).replaceAll("[\\\\/:*?\\[\\]]", "_").trim();
        if (name.isEmpty()) {
            name = "Sheet1";
        }
        return name.length() <= 31 ? name : name.substring(0, 31);
    }
}
