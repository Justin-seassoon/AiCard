package com.aicard.docgen;

import com.aicard.report.repository.DeviceAggregate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ③ 文档生成 · xlsx：把报表聚合结果写成格式化 Excel。
 * **纯确定性**（POI 模板填充，无 LLM、无代码沙箱）。
 */
@Component
public class ReportXlsxWriter {

    private final ObjectMapper mapper;

    public ReportXlsxWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] write(String title, String scopeLabel, String periodLabel, DeviceAggregate agg) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("レポート");

            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);

            int r = 0;
            r = kv(sheet, r, "項目", "値", header);
            r = kv(sheet, r, "タイトル", title, null);
            r = kv(sheet, r, "対象", scopeLabel, null);
            r = kv(sheet, r, "期間", periodLabel, null);
            r = kv(sheet, r, "翻訳ターン数", String.valueOf(agg.turnCount()), null);
            r = kv(sheet, r, "セッション数", String.valueOf(agg.sessionCount()), null);
            r = kv(sheet, r, "QAヒット数", String.valueOf(agg.qaHitCount()), null);
            r = kv(sheet, r, "稼働日数", String.valueOf(agg.activeDays()), null);

            Map<String, Integer> langs = parseLangs(agg.metrics());
            if (!langs.isEmpty()) {
                r++;
                r = kv(sheet, r, "言語", "件数", header);
                for (Map.Entry<String, Integer> e : langs.entrySet()) {
                    r = kv(sheet, r, e.getKey() == null ? "不明" : e.getKey(), String.valueOf(e.getValue()), null);
                }
            }

            sheet.setColumnWidth(0, 24 * 256);
            sheet.setColumnWidth(1, 40 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("xlsx 生成失败", e);
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

    private Map<String, Integer> parseLangs(String metrics) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (metrics == null || metrics.isBlank()) {
            return out;
        }
        try {
            JsonNode langs = mapper.readTree(metrics).path("langs");
            langs.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asInt()));
        } catch (Exception ignore) {
            // metrics 非预期格式 → 不展示语言分布即可
        }
        return out;
    }
}
