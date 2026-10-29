package com.aicard.diagnostic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeviceLogRepository extends JpaRepository<DeviceLog, Long> {

    Optional<DeviceLog> findByLogIdAndCustomerIdAndStoreId(String logId, Long customerId, Long storeId);

    List<DeviceLog> findTop200ByCustomerIdAndStoreIdOrderByCreatedAtDesc(Long customerId, Long storeId);

    List<DeviceLog> findTop200ByCustomerIdAndStoreIdAndDeviceIdOrderByCreatedAtDesc(
            Long customerId, Long storeId, String deviceId);

    List<DeviceLog> findByExpiresAtBeforeAndDeletedAtIsNull(Instant now);

    long countByDeviceIdAndCreatedAtAfter(String deviceId, Instant after);
}
