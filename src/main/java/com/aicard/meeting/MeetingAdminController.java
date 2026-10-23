package com.aicard.meeting;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会议纪要后台接口：列表 / 详情 / 编辑 / 审核 / 导出 / 回听。
 * 租户只从 {@link TenantContext}（X-Customer-Id + X-Store-Id）取，不信任请求体。
 */
@RestController
@RequestMapping("/api/meetings")
public class MeetingAdminController {

    private final MeetingService meetings;
    private final DeviceRepository devices;
    private final ObjectMapper mapper;

    public MeetingAdminController(MeetingService meetings, DeviceRepository devices, ObjectMapper mapper) {
        this.meetings = meetings;
        this.devices = devices;
        this.mapper = mapper;
    }

    public record MeetingSummary(String meetingId, String deviceId, String staffName, String title,
                                 String status, Instant startedAt, Instant endedAt, Integer durationSec,
                                 Integer receivedChunks, List<Integer> missingSeqs,
                                 Instant audioExpiresAt, Instant audioDeletedAt, String errorMessage) {}

    public record ActionDto(Long id, String task, String owner, String dueDate, String status, String sourceRef) {}

    public record MeetingDetail(String meetingId, String deviceId, String staffName, String title,
                                String status, Instant startedAt, Instant endedAt, Integer durationSec,
                                Integer receivedChunks, List<Integer> missingSeqs,
                                Instant audioExpiresAt, Instant audioDeletedAt, boolean audioAvailable,
                                String transcript, Object transcriptJson, Object minutesJson,
                                List<ActionDto> actions, String errorMessage) {}

    public record ActionEditDto(Long id, String task, String owner, String dueDate,
                                String status, String sourceRef) {}

    public record UpdateRequest(String title, Map<String, Object> minutesJson, List<ActionEditDto> actions) {}

    public record ActionStatusRequest(String status) {}

    @GetMapping
    public List<MeetingSummary> list(@RequestParam(required = false) String status) {
        Tenant t = TenantContext.require();
        return meetings.list(t.customerId(), t.storeId(), status).stream().map(this::summary).toList();
    }

    /**
     * 音频导入：上传 WAV（PCM 16-bit）→ 统一转 16k 单声道 → 与设备相同的分片/转写/纪要流程。
     * 用于无麦克风（HTTP 环境）、会议系统录音或手机录音。
     */
    @PostMapping("/import")
    public MeetingDetail importAudio(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "device_id", required = false) String deviceId,
                                     @RequestParam(value = "title", required = false) String title) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file required");
        }
        String filename = file.getOriginalFilename() == null ? "audio.wav" : file.getOriginalFilename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".wav")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 WAV 文件");
        }
        Tenant t = TenantContext.require();
        Device device = resolveDevice(t, deviceId);
        WavPcm.Audio audio;
        try {
            audio = WavPcm.parse(file.getBytes());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        String meetingTitle = (title != null && !title.isBlank()) ? title.trim() : stripExtension(filename);
        if (meetingTitle.length() > 255) {
            meetingTitle = meetingTitle.substring(0, 255);
        }
        return detail(meetings.importPcm(device, meetingTitle, audio.pcm16()));
    }

    @GetMapping("/{meetingId}")
    public MeetingDetail detail(@PathVariable String meetingId) {
        return detail(requireTenant(meetingId));
    }

    @PutMapping("/{meetingId}/minutes")
    public MeetingDetail update(@PathVariable String meetingId, @RequestBody UpdateRequest req) {
        Meeting m = requireTenant(meetingId);
        List<MeetingService.ActionEdit> edits = req.actions() == null ? null : req.actions().stream()
                .map(a -> new MeetingService.ActionEdit(a.id(), a.task(), a.owner(), a.dueDate(),
                        a.status(), a.sourceRef()))
                .toList();
        return detail(meetings.updateMinutes(m, req.title(), req.minutesJson(), edits));
    }

    @PostMapping("/{meetingId}/approve")
    public MeetingDetail approve(@PathVariable String meetingId) {
        return detail(meetings.approve(requireTenant(meetingId)));
    }

    @PostMapping("/{meetingId}/regenerate")
    public MeetingDetail regenerate(@PathVariable String meetingId) {
        return detail(meetings.regenerate(requireTenant(meetingId)));
    }

    @GetMapping("/{meetingId}/export.docx")
    public ResponseEntity<byte[]> export(@PathVariable String meetingId) {
        Meeting m = requireTenant(meetingId);
        byte[] bytes = meetings.exportDocx(m);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"meeting-" + m.getMeetingId() + ".docx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }

    @GetMapping("/{meetingId}/audio")
    public ResponseEntity<byte[]> audio(@PathVariable String meetingId) {
        Meeting m = requireTenant(meetingId);
        byte[] bytes = meetings.audio(m);
        MediaType type = "pcm16".equalsIgnoreCase(m.getCodec())
                ? MediaType.parseMediaType("audio/wav") : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"meeting-" + m.getMeetingId() + "\"")
                .contentType(type)
                .body(bytes);
    }

    @PatchMapping("/actions/{actionId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void actionStatus(@PathVariable Long actionId, @RequestBody ActionStatusRequest req) {
        Tenant t = TenantContext.require();
        meetings.updateActionStatus(t.customerId(), t.storeId(), actionId, req.status());
    }

    private Meeting requireTenant(String meetingId) {
        Tenant t = TenantContext.require();
        return meetings.findForTenant(t.customerId(), t.storeId(), meetingId);
    }

    private MeetingSummary summary(Meeting m) {
        return new MeetingSummary(m.getMeetingId(), m.getDeviceId(), m.getStaffName(), m.getTitle(),
                m.getStatus(), m.getStartedAt(), m.getEndedAt(), m.getDurationSec(),
                m.getLastSeq() == null ? 0 : m.getLastSeq() + 1, meetings.missingList(m),
                m.getAudioExpiresAt(), m.getAudioDeletedAt(), m.getErrorMessage());
    }

    private MeetingDetail detail(Meeting m) {
        List<ActionDto> actions = meetings.actions(m.getMeetingId()).stream()
                .map(a -> new ActionDto(a.getId(), a.getTask(), a.getOwner(), a.getDueDate(),
                        a.getStatus(), a.getSourceRef()))
                .toList();
        return new MeetingDetail(m.getMeetingId(), m.getDeviceId(), m.getStaffName(), m.getTitle(),
                m.getStatus(), m.getStartedAt(), m.getEndedAt(), m.getDurationSec(),
                m.getLastSeq() == null ? 0 : m.getLastSeq() + 1, meetings.missingList(m),
                m.getAudioExpiresAt(), m.getAudioDeletedAt(), m.getAudioDeletedAt() == null,
                m.getTranscript(), parse(m.getTranscriptJson()), parse(m.getMinutesJson()),
                actions, m.getErrorMessage());
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Device resolveDevice(Tenant tenant, String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            Device d = devices.findByDeviceId(deviceId.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found"));
            if (!d.getCustomerId().equals(tenant.customerId()) || !d.getStoreId().equals(tenant.storeId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found");
            }
            return d;
        }
        return devices.findByCustomerIdAndStoreId(tenant.customerId(), tenant.storeId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "本租户没有设备，请指定 device_id"));
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
