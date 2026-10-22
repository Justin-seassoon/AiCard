package com.aicard.docgen.model;

import java.util.List;

/** 单个 sheet 的解析结果。{@code notes}/{@code summaries} 为被识别出的备注/合计行（不计入数据）。 */
public record ParsedSheet(
        String name,
        List<String> headers,
        List<List<String>> rows,
        List<String> notes,
        List<String> summaries,
        List<String> warnings) {}
