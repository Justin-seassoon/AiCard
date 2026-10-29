package com.aicard.meeting;

import com.aicard.common.domain.Device;
import com.aicard.gateway.auth.AuthenticationException;
import com.aicard.gateway.auth.DeviceAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 会议录音设备接口 v1（协议 §4）：
 * <pre>
 * POST /api/v1/meetings                     开始（返回 meeting_id + 分片地址）
 * PUT  /api/v1/meetings/{id}/chunks/{seq}   上传分片（application/octet-stream，幂等）
 * POST /api/v1/meetings/{id}/end            结束（last_seq 必填；有缺片保持 uploading）
 * GET  /api/v1/meetings/{id}                状态查询（重启 / 断网续传）
 * </pre>
 * 鉴权：{@code Authorization: Bearer <device_token>} + {@code X-Device-Id}（与 OTA 共用设备凭证）。
 */
@RestController
@RequestMapping("/api/v1/meetings")
public class MeetingDeviceController {

    private static final String FEATURE_MEETING = "meeting_recording";
    private static final String TRIGGER_LONG_PRESS = "long_press";

    private final DeviceAuthenticator authenticator;
    private final MeetingService meetings;
    private final MeetingConfig config;

    public MeetingDeviceController(DeviceAuthenticator authenticator, MeetingService meetings,
                                   MeetingConfig config) {
        this.authenticator = authenticator;
        this.meetings = meetings;
        this.config = config;
    }

    public record StartRequest(String clientKey, Instant startedAt, String codec,
                               Integer sampleRate, Integer chunkSec, String title,
                               String fwVersion, List<String> features, String trigger,
                               Boolean noticePlayed, String noticeVersion) {}

    public record StartResponse(String meetingId, String status, Integer chunkSec,
                                Integer maxChunkBytes, Integer retentionDays,
                                String chunkUrl, Instant audioExpiresAt) {}

    public record EndRequest(Integer lastSeq) {}

    public record EndResponse(String meetingId, String status, Integer expectedLastSeq,
                              List<Integer> missingSeqs, Integer autoFinalizeAfterSec,
                              Integer durationSec) {}

    public record StatusResponse(String status, Integer lastSeq, Integer expectedLastSeq,
                                 List<Integer> missingSeqs, long uploadedCount,
                                 Instant audioExpiresAt, String errorMessage) {}

    @PostMapping
    public StartResponse start(@RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                               @RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody(required = false) StartRequest req) {
        if (!config.isEnabled()) {
            throw new MeetingException(HttpStatus.FORBIDDEN, "E_MEETING_FEATURE_DISABLED",
                    "meeting feature disabled", false);
        }
        Device device = authenticate(deviceId, authorization);
        validateStart(req);
        Meeting m = meetings.start(device, req.clientKey(), req.startedAt(), req.codec(),
                req.sampleRate(), req.chunkSec(), req.title(),
                new MeetingService.DeviceMeta(req.fwVersion(), req.features(), req.trigger(),
                        req.noticePlayed(), req.noticeVersion()));
        return new StartResponse(m.getMeetingId(), m.getStatus(), m.getChunkSec(),
                config.getMaxChunkBytes(), config.getRetentionDays(),
                "/api/v1/meetings/" + m.getMeetingId() + "/chunks/{seq}", m.getAudioExpiresAt());
    }

    @PutMapping(value = "/{meetingId}/chunks/{seq}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void chunk(@RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                      @RequestHeader(value = "Authorization", required = false) String authorization,
                      @PathVariable String meetingId,
                      @PathVariable int seq,
                      @RequestHeader(value = "X-Chunk-Sha256", required = false) String sha256,
                      @RequestBody byte[] data) {
        Device device = authenticate(deviceId, authorization);
        meetings.uploadChunk(device, meetingId, seq, data, sha256);
    }

    @PostMapping("/{meetingId}/end")
    public EndResponse end(@RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                           @RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable String meetingId,
                           @RequestBody(required = false) EndRequest req) {
        Device device = authenticate(deviceId, authorization);
        Meeting m = meetings.end(device, meetingId, req == null ? null : req.lastSeq());
        return new EndResponse(m.getMeetingId(), m.getStatus(), m.getExpectedLastSeq(),
                meetings.missingList(m), config.getFinalizeAfterMinutes() * 60, m.getDurationSec());
    }

    @GetMapping("/{meetingId}")
    public StatusResponse status(@RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                 @RequestHeader(value = "Authorization", required = false) String authorization,
                                 @PathVariable String meetingId) {
        Device device = authenticate(deviceId, authorization);
        Meeting m = meetings.statusForDevice(device, meetingId);
        return new StatusResponse(m.getStatus(), m.getLastSeq(), m.getExpectedLastSeq(),
                meetings.missingList(m), meetings.uploadedCount(meetingId),
                m.getAudioExpiresAt(), m.getErrorMessage());
    }

    private void validateStart(StartRequest req) {
        if (req == null) {
            throw MeetingException.invalid("request body is required");
        }
        if (isBlank(req.clientKey())) {
            throw MeetingException.invalid("client_key is required");
        }
        if (req.clientKey().length() > 64) {
            throw MeetingException.invalid("client_key is too long");
        }
        if (req.chunkSec() == null) {
            throw MeetingException.invalid("chunk_sec is required");
        }
        if (req.chunkSec() < 5 || req.chunkSec() > 30) {
            throw MeetingException.invalid("chunk_sec must be 5..30");
        }
        if (req.codec() != null && !"pcm16".equalsIgnoreCase(req.codec())) {
            throw MeetingException.invalid("codec must be pcm16");
        }
        if (req.sampleRate() != null && req.sampleRate() != 16000) {
            throw MeetingException.invalid("sample_rate must be 16000");
        }
        if (isBlank(req.fwVersion())) {
            throw MeetingException.invalid("fw_version is required");
        }
        boolean supportsMeeting = req.features() != null && req.features().stream()
                .anyMatch(f -> f != null && FEATURE_MEETING.equals(f.trim()));
        if (!supportsMeeting) {
            throw new MeetingException(HttpStatus.BAD_REQUEST, "E_MEETING_UNSUPPORTED_FW",
                    "features must contain meeting_recording", false);
        }
        if (!TRIGGER_LONG_PRESS.equals(req.trigger())) {
            throw MeetingException.invalid("trigger must be long_press");
        }
        if (!Boolean.TRUE.equals(req.noticePlayed())) {
            throw MeetingException.invalid("notice_played must be true");
        }
    }

    private Device authenticate(String deviceId, String authorization) {
        if (isBlank(deviceId) || authorization == null || !authorization.startsWith("Bearer ")) {
            throw authError();
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw authError();
        }
        try {
            return authenticator.authenticate(deviceId.trim(), token);
        } catch (AuthenticationException | IllegalArgumentException e) {
            throw authError();
        }
    }

    private static MeetingException authError() {
        return new MeetingException(HttpStatus.UNAUTHORIZED, "E_MEETING_AUTH",
                "device credential invalid", false);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
