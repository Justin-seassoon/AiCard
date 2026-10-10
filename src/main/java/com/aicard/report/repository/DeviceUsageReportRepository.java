package com.aicard.report.repository;

import com.aicard.report.domain.DeviceUsageReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DeviceUsageReportRepository extends JpaRepository<DeviceUsageReport, Long> {
    Optional<DeviceUsageReport> findByDeviceIdAndPeriodTypeAndPeriodStart(
            String deviceId, String periodType, LocalDate periodStart);

    List<DeviceUsageReport> findByCustomerIdAndStoreIdAndPeriodTypeOrderByPeriodStartDesc(
            Long customerId, Long storeId, String periodType);

    List<DeviceUsageReport> findByCustomerIdAndStoreIdAndPeriodTypeAndPeriodStartBetween(
            Long customerId, Long storeId, String periodType, LocalDate start, LocalDate end);
}
