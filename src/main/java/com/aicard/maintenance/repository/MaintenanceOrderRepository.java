package com.aicard.maintenance.repository;

import com.aicard.maintenance.domain.MaintenanceOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceOrderRepository extends JpaRepository<MaintenanceOrder, Long> {
    Optional<MaintenanceOrder> findByIdAndCustomerIdAndStoreId(Long id, Long customerId, Long storeId);

    List<MaintenanceOrder> findByCustomerIdAndStoreId(Long customerId, Long storeId);

    List<MaintenanceOrder> findByCustomerIdAndStoreIdAndStatus(Long customerId, Long storeId, String status);

    boolean existsByCustomerIdAndStoreIdAndOrderNo(Long customerId, Long storeId, String orderNo);
}
