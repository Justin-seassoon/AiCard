package com.aicard.diagnostic;

import com.aicard.common.domain.Device;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** 设备诊断日志：上传（限大小/频率）、租户查询、下载、到期清理。 */
@Service
public class DeviceLogService {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> LEVELS = Set.of("debug", "info", "warn", "error");

    private final DeviceLogRepository logs;
    private final DeviceLogStore store;
    private final DeviceLogConfig config;

    public DeviceLogService(DeviceLogRepository logs, DeviceLogStore store, DeviceLogConfig config) {
        this.logs = logs;
        this.store = store;
        this.config = config;
    }

    public DeviceLog upload(Device device, byte[] body, String contentEncoding, String meetingId,
                            String fwVersion, String level, Instant capturedFrom, Instant capturedTo) {
        if (!config.isEnabled()) {
            throw new DeviceLogException(HttpStatus.FORBIDDEN, "E_DEVICE_LOG_DISABLED",
                    "device log upload disabled", false);
        }
        if (body == null || body.length == 0) {
            throw DeviceLogException.invalid("empty log");
        }
        if (body.length > config.getMaxBytes()) {
            throw DeviceLogException.tooLarge("log exceeds " + config.getMaxBytes() + " bytes");
        }
        Instant now = Instant.now();
        if (config.getRateLimitSeconds() > 0
                && logs.countByDeviceIdAndCreatedAtAfter(device.getDeviceId(),
                        now.minusSeconds(config.getRateLimitSeconds())) > 0) {
            throw new DeviceLogException(HttpStatus.TOO_MANY_REQUESTS, "E_DEVICE_LOG_RATE_LIMIT",
                    "too many uploads, retry later", true);
        }

        boolean compressed = "gzip".equalsIgnoreCase(contentEncoding);
        byte[] raw = compressed ? gunzip(body, config.getMaxRawBytes()) : body;
        if (raw.length > config.getMaxRawBytes()) {
            throw DeviceLogException.tooLarge("log exceeds " + config.getMaxRawBytes() + " bytes after decompression");
        }

        String mid = blankToNull(meetingId);
        if (mid != null && !SAFE_ID.matcher(mid).matches()) {
            throw DeviceLogException.invalid("invalid meeting_id");
        }
        String fw = blankToNull(fwVersion);
        if (fw != null && fw.length() > 32) {
            throw DeviceLogException.invalid("fw_version too long");
        }
        if (capturedFrom != null && capturedTo != null && capturedFrom.isAfter(capturedTo)) {
            throw DeviceLogException.invalid("captured_from must be before captured_to");
        }
        String logLevel = normalizeLevel(level);

        String logId = "dlog-" + UUID.randomUUID().toString().replace("-", "");
        String storageKey = store.write(device.getCustomerId(), device.getDeviceId(), logId, compressed, body);
        DeviceLog log = DeviceLog.builder()
                .logId(logId)
                .deviceId(device.getDeviceId())
                .customerId(device.getCustomerId())
                .storeId(device.getStoreId())
                .meetingId(mid)
                .fwVersion(fw)
                .level(logLevel)
                .capturedFrom(capturedFrom)
                .capturedTo(capturedTo)
                .sizeBytes((long) body.length)
                .rawSizeBytes((long) raw.length)
                .compressed(compressed)
                .storageKey(storageKey)
                .createdAt(now)
                .expiresAt(now.plus(config.getRetentionDays(), ChronoUnit.DAYS))
                .build();
        try {
            return logs.save(log);
        } catch (RuntimeException e) {
            store.delete(storageKey);
            throw e;
        }
    }

    public List<DeviceLog> list(Long customerId, Long storeId, String deviceId, String meetingId,
                                Instant from, Instant to, int limit) {
        List<DeviceLog> base = deviceId == null || deviceId.isBlank()
                ? logs.findTop200ByCustomerIdAndStoreIdOrderByCreatedAtDesc(customerId, storeId)
                : logs.findTop200ByCustomerIdAndStoreIdAndDeviceIdOrderByCreatedAtDesc(
                        customerId, storeId, deviceId.trim());
        int max = Math.max(1, Math.min(limit, 200));
        return base.stream()
                .filter(l -> l.getDeletedAt() == null)
                .filter(l -> meetingId == null || meetingId.isBlank() || meetingId.equals(l.getMeetingId()))
                .filter(l -> from == null || !l.getCreatedAt().isBefore(from))
                .filter(l -> to == null || !l.getCreatedAt().isAfter(to))
                .limit(max)
                .toList();
    }

    public DeviceLog require(Long customerId, Long storeId, String logId) {
        return logs.findByLogIdAndCustomerIdAndStoreId(logId, customerId, storeId)
                .orElseThrow(() -> new DeviceLogException(HttpStatus.NOT_FOUND, "E_DEVICE_LOG_NOT_FOUND",
                        "device log not found", false));
    }

    public byte[] read(DeviceLog log) {
        if (log.getDeletedAt() != null || log.getExpiresAt().isBefore(Instant.now())) {
            throw new DeviceLogException(HttpStatus.GONE, "E_DEVICE_LOG_EXPIRED",
                    "device log expired", false);
        }
        byte[] stored;
        try {
            stored = store.read(log.getStorageKey());
        } catch (RuntimeException e) {
            throw new DeviceLogException(HttpStatus.GONE, "E_DEVICE_LOG_EXPIRED",
                    "device log file missing", false);
        }
        return Boolean.TRUE.equals(log.getCompressed()) ? gunzip(stored, config.getMaxRawBytes()) : stored;
    }

    public int cleanupExpired() {
        int count = 0;
        for (DeviceLog log : logs.findByExpiresAtBeforeAndDeletedAtIsNull(Instant.now())) {
            try {
                store.delete(log.getStorageKey());
                log.setDeletedAt(Instant.now());
                logs.save(log);
                count++;
            } catch (RuntimeException ignored) {
                // 单条失败下次再试
            }
        }
        return count;
    }

    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return "info";
        }
        String l = level.trim().toLowerCase(Locale.ROOT);
        if (!LEVELS.contains(l)) {
            throw DeviceLogException.invalid("invalid level");
        }
        return l;
    }

    private static byte[] gunzip(byte[] data, int maxBytes) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw DeviceLogException.tooLarge("log exceeds " + maxBytes + " bytes after decompression");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DeviceLogException e) {
            throw e;
        } catch (IOException e) {
            throw DeviceLogException.invalid("invalid gzip body");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
