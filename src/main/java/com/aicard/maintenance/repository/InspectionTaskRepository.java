package com.aicard.maintenance.repository;

import com.aicard.maintenance.domain.InspectionTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionTaskRepository extends JpaRepository<InspectionTask, Long> {
    Optional<InspectionTask> findByIdAndCustomerIdAndStoreId(Long id, Long customerId, Long storeId);

    List<InspectionTask> findByCustomerIdAndStoreId(Long customerId, Long storeId);

    List<InspectionTask> findByCustomerIdAndStoreIdAndStatus(Long customerId, Long storeId, String status);

    boolean existsByCustomerIdAndStoreIdAndTaskNo(Long customerId, Long storeId, String taskNo);
}
