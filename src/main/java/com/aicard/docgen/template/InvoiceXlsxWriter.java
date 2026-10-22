package com.aicard.docgen.template;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 帳票（見積書 / 請求書）xlsx：宛先・件名・明細・小計/消費税/合計。确定性渲染。 */
@Component
public class InvoiceXlsxWriter {

    public byte[] write(String label, Map<String, Object> fields, List<Map<String, Object>> items) {
        String to = str(fields, "宛先", "");
        String subject = str(fields, "件名", label);
        double taxRate = num(fields.get("taxRate"), 0.1);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeName(label));
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle strong = wb.createCellStyle();
            strong.setFont(bold);

            int r = 0;
            r = kv(sheet, r, label, "", strong);
            if (!to.isBlank()) {
                r = kv(sheet, r, "宛先", to, null);
            }
            r = kv(sheet, r, "件名", subject, null);
            r = kv(sheet, r, "発行日", LocalDate.now().toString(), null);
            r++;

            Row head = sheet.createRow(r++);
            String[] cols = {"品目", "数量", "単価", "金額"};
            for (int c = 0; c < cols.length; c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(cols[c]);
                cell.setCellStyle(strong);
            }

            long subtotal = 0;
            for (Map<String, Object> item : items) {
                long qty = (long) num(item.get("qty"), 0);
                long unit = (long) num(item.get("unitPrice"), 0);
                long amount = qty * unit;
                subtotal += amount;
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(str(item, "name", ""));
                row.createCell(1).setCellValue(qty);
                row.createCell(2).setCellValue(unit);
                row.createCell(3).setCellValue(amount);
            }

            long tax = Math.round(subtotal * taxRate);
            r++;
            r = kv(sheet, r, "小計", String.valueOf(subtotal), null);
            r = kv(sheet, r, "消費税", String.valueOf(tax), null);
            kv(sheet, r, "合計", String.valueOf(subtotal + tax), strong);

            for (int c = 0; c < cols.length; c++) {
                sheet.setColumnWidth(c, 18 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("帳票 xlsx 生成失败", e);
        }
    }

    private static int kv(Sheet sheet, int r, String a, String b, CellStyle style) {
        Row row = sheet.createRow(r);
        Cell c0 = row.createCell(0);
        c0.setCellValue(a);
        Cell c1 = row.createCell(1);
        c1.setCellValue(b);
        if (style != null) {
            c0.setCellStyle(style);
            c1.setCellStyle(style);
        }
        return r + 1;
    }

    private static String safeName(String title) {
        String name = (title == null ? "" : title).replaceAll("[\\\\/:*?\\[\\]]", "_").trim();
        if (name.isEmpty()) {
            name = "Sheet1";
        }
        return name.length() <= 31 ? name : name.substring(0, 31);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static double num(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? def : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
