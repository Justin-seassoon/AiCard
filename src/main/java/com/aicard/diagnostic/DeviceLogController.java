package com.aicard.diagnostic;

import com.aicard.common.domain.Device;
import com.aicard.gateway.auth.AuthenticationException;
import com.aicard.gateway.auth.DeviceAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * 设备诊断日志上传（通用，不限会议场景）：
 * <pre>
 * POST /api/v1/device/logs
 * Authorization: Bearer &lt;device_token&gt;
 * X-Device-Id: &lt;device_id&gt;
 * Content-Encoding: gzip（可选）
 * X-Meeting-Id / X-Fw-Version / X-Log-Level / X-Log-From / X-Log-To（可选）
 * 请求体：纯文本 / JSONL / 任意文本日志（≤256KB，压缩后）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/device/logs")
public class DeviceLogController {

    private final DeviceAuthenticator authenticator;
    private final DeviceLogService logs;
    private final DeviceLogConfig config;

    public DeviceLogController(DeviceAuthenticator authenticator, DeviceLogService logs,
                               DeviceLogConfig config) {
        this.authenticator = authenticator;
        this.logs = logs;
        this.config = config;
    }

    public record UploadResponse(String logId, long sizeBytes, long rawSizeBytes, Instant expiresAt) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                 @RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
                                 @RequestHeader(value = "X-Meeting-Id", required = false) String meetingId,
                                 @RequestHeader(value = "X-Fw-Version", required = false) String fwVersion,
                                 @RequestHeader(value = "X-Log-Level", required = false) String level,
                                 @RequestHeader(value = "X-Log-From", required = false) String from,
                                 @RequestHeader(value = "X-Log-To", required = false) String to,
                                 @RequestHeader(value = "Content-Length", required = false) Long contentLength,
                                 @RequestBody(required = false) byte[] body) {
        Device device = authenticate(deviceId, authorization);
        if (contentLength != null && contentLength > config.getMaxBytes()) {
            throw DeviceLogException.tooLarge("log exceeds " + config.getMaxBytes() + " bytes");
        }
        DeviceLog saved = logs.upload(device, body, contentEncoding, meetingId, fwVersion, level,
                parseInstant(from, "X-Log-From"), parseInstant(to, "X-Log-To"));
        return new UploadResponse(saved.getLogId(), saved.getSizeBytes(), saved.getRawSizeBytes(),
                saved.getExpiresAt());
    }

    private Device authenticate(String deviceId, String authorization) {
        if (deviceId == null || deviceId.isBlank() || authorization == null
                || !authorization.startsWith("Bearer ")) {
            throw DeviceLogException.auth();
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw DeviceLogException.auth();
        }
        try {
            return authenticator.authenticate(deviceId.trim(), token);
        } catch (AuthenticationException | IllegalArgumentException e) {
            throw DeviceLogException.auth();
        }
    }

    private static Instant parseInstant(String value, String header) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            throw DeviceLogException.invalid(header + " must be ISO-8601 instant");
        }
    }
}
