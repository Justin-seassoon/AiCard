package com.aicard.meeting;

import com.aicard.common.domain.Device;
import com.aicard.gateway.auth.AuthenticationException;
import com.aicard.gateway.auth.DeviceAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * 会议录音设备接口（P0）：
 * <pre>
 * POST /api/meetings/start                    开始（返回 meeting_id + 分片地址）
 * PUT  /api/meetings/{id}/chunks/{seq}        上传 30 秒分片（application/octet-stream，幂等）
 * POST /api/meetings/{id}/end                 结束（核对分片完整性并触发转写）
 * </pre>
 * 设备鉴权：{@code X-Device-Id} + {@code X-Device-Token}（与 WSS 握手同一套 token）。
 */
@RestController
@RequestMapping("/api/meetings")
public class MeetingDeviceController {

    private final DeviceAuthenticator authenticator;
    private final MeetingService meetings;

    public MeetingDeviceController(DeviceAuthenticator authenticator, MeetingService meetings) {
        this.authenticator = authenticator;
        this.meetings = meetings;
    }

    public record StartRequest(String clientKey, Instant startedAt, String codec,
                               Integer sampleRate, Integer chunkSec, String title) {}

    public record StartResponse(String meetingId, String status, Integer chunkSec,
                                String chunkUrl, Instant audioExpiresAt) {}

    public record EndRequest(Integer lastSeq) {}

    public record EndResponse(String meetingId, String status, Integer missingCount) {}

    @PostMapping("/start")
    public StartResponse start(@RequestHeader("X-Device-Id") String deviceId,
                               @RequestHeader("X-Device-Token") String token,
                               @RequestBody(required = false) StartRequest req) {
        Device device = authenticate(deviceId, token);
        StartRequest r = req == null ? new StartRequest(null, null, null, null, null, null) : req;
        Meeting m = meetings.start(device, r.clientKey(), r.startedAt(), r.codec(),
                r.sampleRate(), r.chunkSec(), r.title());
        String chunkUrl = "/api/meetings/" + m.getMeetingId() + "/chunks/{seq}";
        return new StartResponse(m.getMeetingId(), m.getStatus(), m.getChunkSec(), chunkUrl, m.getAudioExpiresAt());
    }

    @PutMapping(value = "/{meetingId}/chunks/{seq}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void chunk(@RequestHeader("X-Device-Id") String deviceId,
                      @RequestHeader("X-Device-Token") String token,
                      @PathVariable String meetingId,
                      @PathVariable int seq,
                      @RequestHeader(value = "X-Chunk-Sha256", required = false) String sha256,
                      @RequestBody byte[] data) {
        Device device = authenticate(deviceId, token);
        meetings.uploadChunk(device, meetingId, seq, data, sha256);
    }

    @PostMapping("/{meetingId}/end")
    public EndResponse end(@RequestHeader("X-Device-Id") String deviceId,
                           @RequestHeader("X-Device-Token") String token,
                           @PathVariable String meetingId,
                           @RequestBody(required = false) EndRequest req) {
        Device device = authenticate(deviceId, token);
        Meeting m = meetings.end(device, meetingId, req == null ? null : req.lastSeq());
        return new EndResponse(m.getMeetingId(), m.getStatus(), meetings.missingList(m).size());
    }

    private Device authenticate(String deviceId, String token) {
        try {
            return authenticator.authenticate(deviceId, token);
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }
}
