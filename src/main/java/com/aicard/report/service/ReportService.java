package com.aicard.report.service;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.report.domain.DeviceUsageReport;
import com.aicard.report.repository.DeviceAggregate;
import com.aicard.report.repository.DeviceUsageReportRepository;
import com.aicard.report.repository.ReportQueryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 周报/月报生成：聚合 → 快照落库（唯一键冲突覆盖），支持手动与定时触发。 */
@Service
public class ReportService {

    private final DeviceUsageReportRepository reports;
    private final DeviceRepository devices;
    private final ReportQueryService query;

    public ReportService(DeviceUsageReportRepository reports, DeviceRepository devices, ReportQueryService query) {
        this.reports = reports;
        this.devices = devices;
        this.query = query;
    }

    public DeviceUsageReport generate(Long customerId, Long storeId, String deviceId,
                                      String periodType, LocalDate start, LocalDate end) {
        DeviceAggregate agg = query.aggregate(customerId, storeId, deviceId, start, end);
        DeviceUsageReport report = reports
                .findByDeviceIdAndPeriodTypeAndPeriodStart(deviceId, periodType, start)
                .orElseGet(() -> DeviceUsageReport.builder()
                        .deviceId(deviceId).customerId(customerId).storeId(storeId)
                        .periodType(periodType).periodStart(start).periodEnd(end).build());
        report.setTurnCount(agg.turnCount());
        report.setSessionCount(agg.sessionCount());
        report.setQaHitCount(agg.qaHitCount());
        report.setActiveDays(agg.activeDays());
        report.setMetrics(agg.metrics());
        report.setGeneratedAt(Instant.now());
        return reports.save(report);
    }

    /** 遍历所有租户与设备，逐台生成指定周期的报表。 */
    public int generateDue(String periodType, LocalDate start, LocalDate end) {
        int count = 0;
        for (Object[] pair : devices.findDistinctTenantPairs()) {
            Long customerId = (Long) pair[0];
            Long storeId = (Long) pair[1];
            for (Device d : devices.findByCustomerIdAndStoreId(customerId, storeId)) {
                generate(customerId, storeId, d.getDeviceId(), periodType, start, end);
                count++;
            }
        }
        return count;
    }

    public Optional<DeviceUsageReport> find(String deviceId, String periodType, LocalDate start) {
        return reports.findByDeviceIdAndPeriodTypeAndPeriodStart(deviceId, periodType, start);
    }

    /** 临时聚合（不落快照），供导出用；{@code deviceId} 可空 = 租户级（C4）。 */
    public DeviceAggregate aggregate(Long customerId, Long storeId, String deviceId,
                                     LocalDate start, LocalDate end) {
        return query.aggregate(customerId, storeId, deviceId, start, end);
    }

    public List<DeviceUsageReport> list(Long customerId, Long storeId, String periodType,
                                        LocalDate start, LocalDate end) {
        if (start != null && end != null) {
            return reports.findByCustomerIdAndStoreIdAndPeriodTypeAndPeriodStartBetween(
                    customerId, storeId, periodType, start, end);
        }
        return reports.findByCustomerIdAndStoreIdAndPeriodTypeOrderByPeriodStartDesc(
                customerId, storeId, periodType);
    }
}
