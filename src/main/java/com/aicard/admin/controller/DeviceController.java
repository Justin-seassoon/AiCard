package com.aicard.admin.controller;

import com.aicard.admin.dto.DeviceDto;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 后台设备管理：本租户设备列表 + 创建（绑定租户、生成 token）。 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRepository devices;

    public DeviceController(DeviceRepository devices) {
        this.devices = devices;
    }

    @GetMapping
    public List<DeviceDto> list() {
        Tenant t = TenantContext.require();
        return devices.findByCustomerIdAndStoreId(t.customerId(), t.storeId())
                .stream().map(DeviceDto::from).toList();
    }

    @PostMapping
    public DeviceDto create(@RequestBody DeviceDto req) {
        Tenant t = TenantContext.require();
        Device d = Device.builder()
                .deviceId(req.deviceId())
                .customerId(t.customerId())
                .storeId(t.storeId())
                .token(UUID.randomUUID().toString())
                .staffLanguage(req.staffLanguage())
                .createdAt(Instant.now())
                .build();
        return DeviceDto.from(devices.save(d));
    }
}
