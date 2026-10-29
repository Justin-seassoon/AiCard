package com.aicard.diagnostic;

import com.aicard.common.domain.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DeviceLogServiceTest {

    private DeviceLogRepository repo;
    private DeviceLogStore store;
    private DeviceLogConfig config;
    private DeviceLogService service;

    @BeforeEach
    void setUp() {
        repo = mock(DeviceLogRepository.class);
        store = mock(DeviceLogStore.class);
        config = mock(DeviceLogConfig.class);
        when(config.isEnabled()).thenReturn(true);
        when(config.getMaxBytes()).thenReturn(1024);
        when(config.getMaxRawBytes()).thenReturn(4096);
        when(config.getRateLimitSeconds()).thenReturn(60);
        when(config.getRetentionDays()).thenReturn(7);
        when(store.write(any(), anyString(), anyString(), anyBoolean(), any())).thenReturn("7/dev-1/x.log");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new DeviceLogService(repo, store, config);
    }

    @Test
    void uploadStoresPlainLogWithMetadata() {
        Device device = device();
        DeviceLog log = service.upload(device, "hello".getBytes(), null, "mtg-1", "1.0.0",
                "warn", Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now());

        assertThat(log.getLogId()).startsWith("dlog-");
        assertThat(log.getDeviceId()).isEqualTo("dev-1");
        assertThat(log.getCustomerId()).isEqualTo(7L);
        assertThat(log.getMeetingId()).isEqualTo("mtg-1");
        assertThat(log.getLevel()).isEqualTo("warn");
        assertThat(log.getSizeBytes()).isEqualTo(5);
        assertThat(log.getRawSizeBytes()).isEqualTo(5);
        assertThat(log.getCompressed()).isFalse();
        assertThat(log.getExpiresAt()).isAfter(Instant.now());
        verify(store).write(eq(7L), eq("dev-1"), startsWith("dlog-"), eq(false), any());
    }

    @Test
    void uploadAcceptsGzipAndRecordsBothSizes() throws Exception {
        byte[] gz = gzip("0123456789".getBytes());
        DeviceLog log = service.upload(device(), gz, "gzip", null, null, null, null, null);

        assertThat(log.getCompressed()).isTrue();
        assertThat(log.getSizeBytes()).isEqualTo(gz.length);
        assertThat(log.getRawSizeBytes()).isEqualTo(10);
    }

    @Test
    void uploadRejectsOversizedBody() {
        assertThatThrownBy(() -> service.upload(device(), new byte[2000], null, null, null, null, null, null))
                .isInstanceOf(DeviceLogException.class)
                .extracting(e -> ((DeviceLogException) e).getErrorCode())
                .isEqualTo("E_DEVICE_LOG_TOO_LARGE");
    }

    @Test
    void uploadRejectsRateLimitedDevice() {
        when(repo.countByDeviceIdAndCreatedAtAfter(eq("dev-1"), any())).thenReturn(1L);
        assertThatThrownBy(() -> service.upload(device(), "hi".getBytes(), null, null, null, null, null, null))
                .isInstanceOf(DeviceLogException.class)
                .extracting(e -> ((DeviceLogException) e).getErrorCode())
                .isEqualTo("E_DEVICE_LOG_RATE_LIMIT");
        verify(store, never()).write(any(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void uploadRejectsInvalidMeetingId() {
        assertThatThrownBy(() -> service.upload(device(), "hi".getBytes(), null, "../../etc/passwd",
                null, null, null, null))
                .isInstanceOf(DeviceLogException.class)
                .extracting(e -> ((DeviceLogException) e).getErrorCode())
                .isEqualTo("E_DEVICE_LOG_INVALID_ARGUMENT");
    }

    @Test
    void readDecompressesGzipAndRejectsExpired() {
        DeviceLog log = DeviceLog.builder().logId("dlog-1").compressed(true)
                .storageKey("7/dev-1/dlog-1.log.gz")
                .expiresAt(Instant.now().plusSeconds(60)).build();
        when(repo.findByLogIdAndCustomerIdAndStoreId("dlog-1", 7L, 8L)).thenReturn(Optional.of(log));
        when(store.read("7/dev-1/dlog-1.log.gz")).thenReturn(gzipBytes("abc".getBytes()));

        assertThat(new String(service.read(log))).isEqualTo("abc");

        log.setExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.read(log))
                .isInstanceOf(DeviceLogException.class)
                .extracting(e -> ((DeviceLogException) e).getErrorCode())
                .isEqualTo("E_DEVICE_LOG_EXPIRED");
    }

    @Test
    void readMapsMissingFileToExpired() {
        DeviceLog log = DeviceLog.builder().logId("dlog-1").compressed(false)
                .storageKey("7/dev-1/dlog-1.log")
                .expiresAt(Instant.now().plusSeconds(60)).build();
        when(store.read("7/dev-1/dlog-1.log")).thenThrow(new java.io.UncheckedIOException(
                new java.io.IOException("missing")));

        assertThatThrownBy(() -> service.read(log))
                .isInstanceOf(DeviceLogException.class)
                .extracting(e -> ((DeviceLogException) e).getErrorCode())
                .isEqualTo("E_DEVICE_LOG_EXPIRED");
    }

    @Test
    void cleanupDeletesFilesAndMarksDeleted() {
        DeviceLog log = DeviceLog.builder().logId("dlog-1").storageKey("7/dev-1/dlog-1.log")
                .expiresAt(Instant.now().minusSeconds(1)).build();
        when(repo.findByExpiresAtBeforeAndDeletedAtIsNull(any())).thenReturn(java.util.List.of(log));

        assertThat(service.cleanupExpired()).isEqualTo(1);

        verify(store).delete("7/dev-1/dlog-1.log");
        assertThat(log.getDeletedAt()).isNotNull();
        verify(repo).save(log);
    }

    private static Device device() {
        return Device.builder().deviceId("dev-1").customerId(7L).storeId(8L).token("token").build();
    }

    private static byte[] gzip(byte[] data) throws Exception {
        return gzipBytes(data);
    }

    private static byte[] gzipBytes(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(data);
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
