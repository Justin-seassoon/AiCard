package com.aicard.admin.dto;

import com.aicard.common.domain.Device;

public record DeviceDto(Long id, String deviceId, Long customerId, Long storeId, String staffLanguage) {
    public static DeviceDto from(Device d) {
        return new DeviceDto(d.getId(), d.getDeviceId(), d.getCustomerId(), d.getStoreId(), d.getStaffLanguage());
    }
}
