package com.aicard.report.service;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.report.domain.DeviceUsageReport;
import com.aicard.report.repository.DeviceAggregate;
import com.aicard.report.repository.DeviceUsageReportRepository;
import com.aicard.report.repository.ReportQueryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private final DeviceUsageReportRepository reports = mock(DeviceUsageReportRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final ReportQueryService query = mock(ReportQueryService.class);
    private final ReportService service = new ReportService(reports, devices, query);

    @Test
    void generateOverwritesExistingSnapshot() {
        DeviceUsageReport existing = DeviceUsageReport.builder()
                .id(1L).deviceId("dev-a").customerId(10L).storeId(20L)
                .periodType("weekly").periodStart(LocalDate.of(2026, 10, 5)).periodEnd(LocalDate.of(2026, 10, 12))
                .turnCount(0).build();
        when(reports.findByDeviceIdAndPeriodTypeAndPeriodStart("dev-a", "weekly", LocalDate.of(2026, 10, 5)))
                .thenReturn(Optional.of(existing));
        when(query.aggregate(10L, 20L, "dev-a", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)))
                .thenReturn(new DeviceAggregate(5, 2, 1, 3, "{\"langs\":{}}"));
        when(reports.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeviceUsageReport result = service.generate(10L, 20L, "dev-a", "weekly",
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12));

        assertThat(result.getId()).isEqualTo(1L); // 复用已有快照
        assertThat(result.getTurnCount()).isEqualTo(5);
        assertThat(result.getQaHitCount()).isEqualTo(1);
    }

    @Test
    void generateDueIteratesAllTenantsAndDevices() {
        when(devices.findDistinctTenantPairs()).thenReturn(List.<Object[]>of(new Object[]{10L, 20L}));
        when(devices.findByCustomerIdAndStoreId(10L, 20L)).thenReturn(List.of(
                Device.builder().deviceId("dev-a").customerId(10L).storeId(20L).build(),
                Device.builder().deviceId("dev-b").customerId(10L).storeId(20L).build()));
        when(reports.findByDeviceIdAndPeriodTypeAndPeriodStart(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(query.aggregate(any(), any(), any(), any(), any()))
                .thenReturn(new DeviceAggregate(0, 0, 0, 0, "{}"));
        when(reports.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.generateDue("weekly", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12));

        assertThat(count).isEqualTo(2);
    }
}
