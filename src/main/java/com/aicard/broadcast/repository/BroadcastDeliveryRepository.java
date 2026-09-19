package com.aicard.broadcast.repository;

import com.aicard.broadcast.domain.BroadcastDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BroadcastDeliveryRepository extends JpaRepository<BroadcastDelivery, Long> {

    List<BroadcastDelivery> findByBroadcastId(Long broadcastId);

    /** 某设备尚未投递的广播（重连后补拉用）。 */
    List<BroadcastDelivery> findByDeviceIdAndDeliveredAtIsNull(String deviceId);

    Optional<BroadcastDelivery> findByBroadcastIdAndDeviceId(Long broadcastId, String deviceId);
}
