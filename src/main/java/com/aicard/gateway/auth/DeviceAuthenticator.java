package com.aicard.gateway.auth;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import org.springframework.stereotype.Component;

/**
 * 设备鉴权：device_id + token → Device（据此推导 customer_id/store_id）。
 * demo 阶段静态 token；真实上线短期凭证轮换在此替换（见 spec §11.5）。
 */
@Component
public class DeviceAuthenticator {

    private final DeviceRepository devices;

    public DeviceAuthenticator(DeviceRepository devices) {
        this.devices = devices;
    }

    public Device authenticate(String deviceId, String token) {
        Device device = devices.findByDeviceId(deviceId)
                .orElseThrow(() -> new AuthenticationException("unknown device"));
        if (!device.getToken().equals(token)) {
            throw new AuthenticationException("invalid token");
        }
        return device;
    }
}
