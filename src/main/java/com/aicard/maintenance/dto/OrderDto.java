package com.aicard.maintenance.dto;

import com.aicard.maintenance.domain.MaintenanceOrder;

import java.time.Instant;

public record OrderDto(Long id, String orderNo, String deviceId, String title, String description,
                       String status, String reporter, Instant createdAt) {
    public static OrderDto from(MaintenanceOrder o) {
        return new OrderDto(o.getId(), o.getOrderNo(), o.getDeviceId(), o.getTitle(), o.getDescription(),
                o.getStatus(), o.getReporter(), o.getCreatedAt());
    }
}
