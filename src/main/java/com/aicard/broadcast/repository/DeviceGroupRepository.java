package com.aicard.broadcast.repository;

import com.aicard.broadcast.domain.DeviceGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceGroupRepository extends JpaRepository<DeviceGroup, Long> {

    List<DeviceGroup> findByCustomerIdAndStoreId(Long customerId, Long storeId);

    Optional<DeviceGroup> findByIdAndCustomerIdAndStoreId(Long id, Long customerId, Long storeId);
}
