package com.aicard.docgen;

import com.aicard.docgen.model.ParsedSheet;
import com.aicard.docgen.model.ParsedWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日本企业现实场景的表格解析（xlsx / xls / csv → 结构化）。
 *
 * <p>处理：合并表头（含两级）、全角数字、多 sheet、底部备注/合计行、和暦日期、前導零。
 * 文件内容一律按**不可信数据**清洗（{@link FileSanitizer}），解析只在内存进行、不落盘。
 * 解析不确定处**只给 warning，不猜测**。
 */
@Component
public class SpreadsheetParser {

    public static final int MAX_SHEETS = 20;
    public static final int MAX_ROWS = 5000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern WAREKI = Pattern.compile("(令和|平成|昭和)(\\d+|元)年(\\d+)月(?:(\\d+)日)?");
    private static final Pattern NOTE = Pattern.compile("^(注[:：]|※|備考|注記)");
    private static final Pattern SUMMARY = Pattern.compile("^(合計|総計|小計|計[:：])");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern LEADING_ZERO = Pattern.compile("^0\\d+$");

    private final FileSanitizer sanitizer;

    public SpreadsheetParser(FileSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public ParsedWorkbook parse(InputStream in, String filename) throws Exception {
        List<String> warnings = new ArrayList<>();
        List<ParsedSheet> sheets = new ArrayList<>();
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".csv")) {
            sheets.add(parseCsv(in));
        } else {
            try (Workbook wb = WorkbookFactory.create(in)) {
                int n = wb.getNumberOfSheets();
                if (n > MAX_SHEETS) {
                    warnings.add("sheet 数超过 " + MAX_SHEETS + "，仅解析前 " + MAX_SHEETS + " 个");
                }
                FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
                for (int i = 0; i < Math.min(n, MAX_SHEETS); i++) {
                    sheets.add(parseSheet(wb.getSheetAt(i), eval));
                }
            }
        }
        return new ParsedWorkbook(filename, sheets, warnings);
    }

    // ---------------- Excel ----------------

    private ParsedSheet parseSheet(Sheet sheet, FormulaEvaluator eval) {
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) {
            return new ParsedSheet(sheet.getSheetName(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        int rowCap = Math.min(lastRow + 1, MAX_ROWS + 100);
        int maxCols = 0;
        for (int r = 0; r < rowCap; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                maxCols = Math.max(maxCols, row.getLastCellNum());
            }
        }
        if (maxCols <= 0) {
            return new ParsedSheet(sheet.getSheetName(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        String[][] matrix = new String[rowCap][maxCols];
        for (int r = 0; r < rowCap; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                Arrays.fill(matrix[r], "");
                continue;
            }
            for (int c = 0; c < maxCols; c++) {
                Value v = readCell(c < row.getLastCellNum() ? row.getCell(c) : null, eval);
                matrix[r][c] = v.text();
            }
        }

        // 合并单元格：用左上角值填充整块（纵向合并 → 两级表头可识别）
        for (CellRangeAddress rg : sheet.getMergedRegions()) {
            int r0 = Math.max(0, rg.getFirstRow());
            int c0 = Math.max(0, rg.getFirstColumn());
            if (r0 >= rowCap || c0 >= maxCols) {
                continue;
            }
            String v = matrix[r0][c0];
            for (int r = r0; r <= rg.getLastRow() && r < rowCap; r++) {
                for (int c = c0; c <= rg.getLastColumn() && c < maxCols; c++) {
                    matrix[r][c] = v;
                }
            }
        }

        boolean twoLevel = sheet.getMergedRegions().stream()
                .anyMatch(rg -> rg.getFirstRow() >= 0 && rg.getLastRow() > rg.getFirstRow());
        return buildSheet(sheet.getSheetName(), matrix, twoLevel);
    }

    private Value readCell(Cell cell, FormulaEvaluator eval) {
        if (cell == null) {
            return new Value("", false);
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                type = eval.evaluateFormulaCell(cell);
            } catch (Exception e) {
                type = CellType.STRING;
            }
        }
        return switch (type) {
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield new Value(cell.getLocalDateTimeCellValue().toLocalDate().format(DATE), false);
                }
                double d = cell.getNumericCellValue();
                yield new Value(d == Math.rint(d) && Math.abs(d) < 1e15
                        ? String.valueOf((long) d) : String.valueOf(d), true);
            }
            case BOOLEAN -> new Value(String.valueOf(cell.getBooleanCellValue()), false);
            case STRING -> new Value(cell.getStringCellValue(), false);
            default -> new Value("", false);
        };
    }

    // ---------------- CSV ----------------

    private ParsedSheet parseCsv(InputStream in) throws Exception {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        List<List<String>> raw = splitCsv(content);
        int cols = raw.stream().mapToInt(List::size).max().orElse(0);
        String[][] matrix = new String[raw.size()][cols];
        for (int r = 0; r < raw.size(); r++) {
            List<String> row = raw.get(r);
            for (int c = 0; c < cols; c++) {
                matrix[r][c] = c < row.size() ? row.get(c) : "";
            }
        }
        return buildSheet("CSV", matrix, false);
    }

    private static List<List<String>> splitCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // ---------------- 共用：表头/数据/备注识别 ----------------

    private ParsedSheet buildSheet(String name, String[][] matrix, boolean twoLevel) {
        List<String> warnings = new ArrayList<>();
        int rowCount = matrix.length;
        int cols = rowCount == 0 ? 0 : matrix[0].length;

        for (int r = 0; r < rowCount; r++) {
            for (int c = 0; c < cols; c++) {
                matrix[r][c] = sanitizeCell(normalize(matrix[r][c]), warnings);
            }
        }

        int headerStart = -1;
        for (int r = 0; r < rowCount; r++) {
            if (distinctNonEmpty(matrix[r]) >= 2) {
                headerStart = r;
                break;
            }
        }
        if (headerStart < 0) {
            return new ParsedSheet(name, List.of(), List.of(), List.of(), List.of(), warnings);
        }
        boolean multi = twoLevel && headerStart + 1 < rowCount && distinctNonEmpty(matrix[headerStart + 1]) >= 2;

        List<String> headers = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            LinkedHashSet<String> parts = new LinkedHashSet<>();
            int end = multi ? headerStart + 1 : headerStart;
            for (int r = headerStart; r <= end; r++) {
                if (!matrix[r][c].isBlank()) {
                    parts.add(matrix[r][c]);
                }
            }
            headers.add(String.join(" / ", parts));
        }

        List<List<String>> data = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        boolean warnedZero = false;

        for (int r = (multi ? headerStart + 2 : headerStart + 1); r < rowCount; r++) {
            String[] row = matrix[r];
            if (allBlank(row)) {
                continue;
            }
            String first = firstNonEmpty(row);
            if (SUMMARY.matcher(first).find()) {
                summaries.add(join(row));
                continue;
            }
            if (NOTE.matcher(first).find()) {
                notes.add(join(row));
                continue;
            }
            if (data.size() >= MAX_ROWS) {
                warnings.add("数据行超过 " + MAX_ROWS + "，已截断");
                break;
            }
            for (String cell : row) {
                if (!warnedZero && LEADING_ZERO.matcher(cell).matches()) {
                    warnings.add("检测到前導零的值（如 " + cell + "），已按文本保留");
                    warnedZero = true;
                }
            }
            data.add(new ArrayList<>(Arrays.asList(row)));
        }

        if (data.isEmpty()) {
            warnings.add("未识别到数据行");
        }
        return new ParsedSheet(name, headers, data, notes, summaries, warnings);
    }

    /** 全角→半角 + 和暦→西暦 + trim。 */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if ((ch >= '０' && ch <= '９') || (ch >= 'Ａ' && ch <= 'Ｚ') || (ch >= 'ａ' && ch <= 'ｚ')) {
                sb.append((char) (ch - 0xFEE0));
            } else if (ch == '　') {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        return wareki(sb.toString()).trim();
    }

    /** 令和/平成/昭和 N年M月D日 → 西暦。 */
    static String wareki(String s) {
        Matcher m = WAREKI.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int base = switch (m.group(1)) {
                case "令和" -> 2018;
                case "平成" -> 1988;
                default -> 1925;
            };
            int y = "元".equals(m.group(2)) ? 1 : Integer.parseInt(m.group(2));
            String md = m.group(4) == null ? m.group(3) + "月" : m.group(3) + "月" + m.group(4) + "日";
            m.appendReplacement(out, (base + y) + "年" + md);
        }
        m.appendTail(out);
        return out.toString();
    }

    private String sanitizeCell(String text, List<String> warnings) {
        FileSanitizer.Cell cell = sanitizer.analyze(text);
        if (cell.suspicious()) {
            warnings.add("检测到疑似提示注入的内容，已标记：" + abbreviate(text));
        }
        return cell.text();
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private static int countNonEmpty(String[] row) {
        int n = 0;
        for (String s : row) {
            if (s != null && !s.isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** 不同的非空值个数——标题行（合并跨整行）填充后值全相同，借此与真表头区分。 */
    private static int distinctNonEmpty(String[] row) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : row) {
            if (s != null && !s.isBlank()) {
                set.add(s);
            }
        }
        return set.size();
    }

    private static boolean allBlank(String[] row) {
        return countNonEmpty(row) == 0;
    }

    private static String firstNonEmpty(String[] row) {
        for (String s : row) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    private static String join(String[] row) {
        return String.join(" | ", row).trim();
    }

    private record Value(String text, boolean numeric) {}
}
