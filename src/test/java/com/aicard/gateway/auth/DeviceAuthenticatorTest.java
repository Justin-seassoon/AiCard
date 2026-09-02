package com.aicard.gateway.auth;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceAuthenticatorTest {

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceAuthenticator auth = new DeviceAuthenticator(devices);

    @Test
    void authenticatesValidDevice() {
        Device d = Device.builder().deviceId("dev-1").token("secret").customerId(1L).storeId(2L).build();
        when(devices.findByDeviceId("dev-1")).thenReturn(Optional.of(d));
        assertThat(auth.authenticate("dev-1", "secret").getCustomerId()).isEqualTo(1L);
    }

    @Test
    void rejectsUnknownDevice() {
        when(devices.findByDeviceId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.authenticate("nope", "secret"))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void rejectsWrongToken() {
        Device d = Device.builder().deviceId("dev-1").token("secret").customerId(1L).storeId(2L).build();
        when(devices.findByDeviceId("dev-1")).thenReturn(Optional.of(d));
        assertThatThrownBy(() -> auth.authenticate("dev-1", "wrong"))
                .isInstanceOf(AuthenticationException.class);
    }
}
