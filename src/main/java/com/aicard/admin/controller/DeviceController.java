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
    public DeviceCreateResponse create(@RequestBody DeviceDto req) {
        Tenant t = TenantContext.require();
        Device d = Device.builder()
                .deviceId(req.deviceId())
                .customerId(t.customerId())
                .storeId(t.storeId())
                .token(UUID.randomUUID().toString())
                .staffLanguage(req.staffLanguage())
                .staffName(req.staffName())
                .staffNo(req.staffNo())
                .createdAt(Instant.now())
                .build();
        return DeviceCreateResponse.of(devices.save(d));
    }

    /** 设备注册响应：含一次性 token（仅注册时返回；列表接口不返回 token，避免泄露全部设备凭证）。 */
    public record DeviceCreateResponse(String deviceId, String token, Long customerId, Long storeId,
                                       String staffLanguage, String staffName, String staffNo) {
        public static DeviceCreateResponse of(Device d) {
            return new DeviceCreateResponse(d.getDeviceId(), d.getToken(), d.getCustomerId(), d.getStoreId(),
                    d.getStaffLanguage(), d.getStaffName(), d.getStaffNo());
        }
    }
}
