package com.aicard.notification.repository;

import com.aicard.notification.domain.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    List<NotificationDelivery> findByNotificationId(Long notificationId);

    List<NotificationDelivery> findByDeviceIdAndDeliveredAtIsNull(String deviceId);
}
