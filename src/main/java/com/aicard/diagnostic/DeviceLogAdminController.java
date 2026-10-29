package com.aicard.diagnostic;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** 后台设备诊断日志：本租户列表 + 下载（文本）。 */
@RestController
@RequestMapping("/api/device-logs")
public class DeviceLogAdminController {

    private final DeviceLogService logs;

    public DeviceLogAdminController(DeviceLogService logs) {
        this.logs = logs;
    }

    public record LogSummary(String logId, String deviceId, String meetingId, String fwVersion,
                             String level, Instant capturedFrom, Instant capturedTo,
                             Long sizeBytes, Long rawSizeBytes, Boolean compressed,
                             Instant createdAt, Instant expiresAt) {}

    @GetMapping
    public List<LogSummary> list(@RequestParam(required = false) String deviceId,
                                 @RequestParam(name = "meeting_id", required = false) String meetingId,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false, defaultValue = "100") int limit) {
        Tenant t = TenantContext.require();
        return logs.list(t.customerId(), t.storeId(), deviceId, meetingId,
                        parseInstant(from), parseInstant(to), limit)
                .stream()
                .map(l -> new LogSummary(l.getLogId(), l.getDeviceId(), l.getMeetingId(),
                        l.getFwVersion(), l.getLevel(), l.getCapturedFrom(), l.getCapturedTo(),
                        l.getSizeBytes(), l.getRawSizeBytes(), l.getCompressed(),
                        l.getCreatedAt(), l.getExpiresAt()))
                .toList();
    }

    @GetMapping("/{logId}")
    public ResponseEntity<byte[]> download(@PathVariable String logId) {
        Tenant t = TenantContext.require();
        DeviceLog log = logs.require(t.customerId(), t.storeId(), logId);
        byte[] data = logs.read(log);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + log.getLogId() + ".log\"")
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(data);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid time, expected ISO-8601 instant");
        }
    }
}
