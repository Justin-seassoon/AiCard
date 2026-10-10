package com.aicard.maintenance.repository;

import com.aicard.maintenance.domain.InspectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {
    List<InspectionRecord> findByTaskId(Long taskId);

    List<InspectionRecord> findByCustomerIdAndStoreId(Long customerId, Long storeId);
}
