package com.aicard.report.dto;

import com.aicard.report.domain.DeviceUsageReport;

import java.time.Instant;
import java.time.LocalDate;

public record ReportDto(Long id, String deviceId, String periodType, LocalDate periodStart, LocalDate periodEnd,
                        int turnCount, int sessionCount, int qaHitCount, int activeDays,
                        String metrics, Instant generatedAt) {
    public static ReportDto from(DeviceUsageReport r) {
        return new ReportDto(r.getId(), r.getDeviceId(), r.getPeriodType(), r.getPeriodStart(), r.getPeriodEnd(),
                r.getTurnCount() == null ? 0 : r.getTurnCount(),
                r.getSessionCount() == null ? 0 : r.getSessionCount(),
                r.getQaHitCount() == null ? 0 : r.getQaHitCount(),
                r.getActiveDays() == null ? 0 : r.getActiveDays(),
                r.getMetrics(), r.getGeneratedAt());
    }
}
