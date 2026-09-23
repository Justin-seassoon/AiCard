package com.aicard.admin.dto;

import com.aicard.common.domain.Device;

public record DeviceDto(Long id, String deviceId, Long customerId, Long storeId, String staffLanguage,
                        String productModel, String hardwareVersion, String lastP4Version, String lastC5Version) {
    public static DeviceDto from(Device d) {
        return new DeviceDto(d.getId(), d.getDeviceId(), d.getCustomerId(), d.getStoreId(), d.getStaffLanguage(),
                d.getProductModel(), d.getHardwareVersion(), d.getLastP4Version(), d.getLastC5Version());
    }
}
