package com.aicard.diagnostic;

import com.aicard.common.domain.Device;
import com.aicard.gateway.auth.DeviceAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceLogControllerTest {

    private DeviceAuthenticator authenticator;
    private DeviceLogService logs;
    private DeviceLogConfig config;
    private DeviceLogController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DeviceAuthenticator.class);
        logs = mock(DeviceLogService.class);
        config = mock(DeviceLogConfig.class);
        when(config.getMaxBytes()).thenReturn(1024);
        controller = new DeviceLogController(authenticator, logs, config);
        when(authenticator.authenticate("dev-1", "token")).thenReturn(
                Device.builder().deviceId("dev-1").customerId(7L).storeId(8L).build());
    }

    @Test
    void uploadRejectsInvalidBearer() {
        assertError(() -> controller.upload("dev-1", "token", null, null, null, null, null, null,
                null, "hi".getBytes()), "E_DEVICE_LOG_AUTH");
        verifyNoInteractions(logs);
    }

    @Test
    void uploadRejectsOversizedContentLengthBeforeReading() {
        assertError(() -> controller.upload("dev-1", "Bearer token", null, null, null, null, null, null,
                4096L, new byte[1]), "E_DEVICE_LOG_TOO_LARGE");
        verifyNoInteractions(logs);
    }

    @Test
    void uploadRejectsInvalidCapturedTime() {
        assertError(() -> controller.upload("dev-1", "Bearer token", null, null, null, null,
                "not-a-time", null, null, "hi".getBytes()), "E_DEVICE_LOG_INVALID_ARGUMENT");
        verifyNoInteractions(logs);
    }

    @Test
    void uploadReturnsStoredMetadata() {
        Instant expires = Instant.now().plusSeconds(3600);
        DeviceLog saved = DeviceLog.builder().logId("dlog-1").sizeBytes(2L)
                .rawSizeBytes(2L).expiresAt(expires).build();
        when(logs.upload(any(), any(), eq(null), eq("mtg-1"), eq("1.0.0"), eq("warn"), any(), any()))
                .thenReturn(saved);

        DeviceLogController.UploadResponse res = controller.upload("dev-1", "Bearer token", null,
                "mtg-1", "1.0.0", "warn", null, null, null, "hi".getBytes());

        assertThat(res.logId()).isEqualTo("dlog-1");
        assertThat(res.sizeBytes()).isEqualTo(2);
        assertThat(res.expiresAt()).isEqualTo(expires);
    }

    private static void assertError(Runnable call, String errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(DeviceLogException.class)
                .extracting(e -> ((DeviceLogException) e).getErrorCode())
                .isEqualTo(errorCode);
    }
}
