package com.aicard.common.repository;

import com.aicard.common.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByDeviceId(String deviceId);

    List<Device> findByCustomerIdAndStoreId(Long customerId, Long storeId);
}
