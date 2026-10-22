package com.aicard.docgen.model;

import java.util.List;

/** 一个文件（多 sheet）的解析结果。 */
public record ParsedWorkbook(String filename, List<ParsedSheet> sheets, List<String> warnings) {}
