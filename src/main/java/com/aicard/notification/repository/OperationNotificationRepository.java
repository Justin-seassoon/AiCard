package com.aicard.notification.repository;

import com.aicard.notification.domain.OperationNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OperationNotificationRepository extends JpaRepository<OperationNotification, Long> {
    Optional<OperationNotification> findByIdAndCustomerIdAndStoreId(Long id, Long customerId, Long storeId);

    List<OperationNotification> findByCustomerIdAndStoreId(Long customerId, Long storeId);

    List<OperationNotification> findByCustomerIdAndStoreIdAndStatusAndScheduleAtLessThanEqual(
            Long customerId, Long storeId, String status, Instant now);
}
