package com.aicard.report.dto;

import java.time.LocalDate;

/** 手动生成报表请求。 */
public record ReportRequest(String deviceId, String periodType, LocalDate start, LocalDate end) {}
