package com.aicard.meeting;

import com.aicard.common.domain.Device;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 会议录音主流程：开始 → 分片上传（幂等/断点续传）→ 结束核对 → 异步转写/纪要。
 * <p>设备侧按 device_id + token 鉴权；后台按 TenantContext 隔离。
 * 会议状态与租户都落在会议行上，异步处理不依赖 ThreadLocal（C5）。</p>
 */
@Service
public class MeetingService {

    private static final int MAX_SEQ = 100_000;

    private final MeetingRepository meetings;
    private final MeetingChunkRepository chunks;
    private final MeetingActionItemRepository actionRepo;
    private final MeetingAudioStore audioStore;
    private final MeetingConfig config;
    private final MeetingProcessor processor;
    private final MinutesService minutes;
    private final ObjectMapper mapper;

    public MeetingService(MeetingRepository meetings, MeetingChunkRepository chunks,
                          MeetingActionItemRepository actionRepo, MeetingAudioStore audioStore,
                          MeetingConfig config, MeetingProcessor processor, MinutesService minutes,
                          ObjectMapper mapper) {
        this.meetings = meetings;
        this.chunks = chunks;
        this.actionRepo = actionRepo;
        this.audioStore = audioStore;
        this.config = config;
        this.processor = processor;
        this.minutes = minutes;
        this.mapper = mapper;
    }

    public record ActionEdit(Long id, String task, String owner, String dueDate, String status, String sourceRef) {}

    // ===================== 设备侧 =====================

    public Meeting start(Device device, String clientKey, Instant startedAt, String codec,
                         Integer sampleRate, Integer chunkSec, String title) {
        String key = blankToNull(clientKey);
        if (key != null) {
            Optional<Meeting> existing = meetings.findByDeviceIdAndClientKey(device.getDeviceId(), key);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        Instant now = Instant.now();
        // 端侧时钟可能不准：只接受「近 1 天 ~ 未来 5 分钟」的 started_at，否则用云端时间
        Instant begin = (startedAt == null
                || startedAt.isAfter(now.plus(5, ChronoUnit.MINUTES))
                || startedAt.isBefore(now.minus(1, ChronoUnit.DAYS))) ? now : startedAt;
        Meeting meeting = Meeting.builder()
                .meetingId("mtg-" + UUID.randomUUID().toString().replace("-", ""))
                .clientKey(key)
                .customerId(device.getCustomerId())
                .storeId(device.getStoreId())
                .deviceId(device.getDeviceId())
                .staffName(device.getStaffName())
                .title(blankToNull(title))
                .status(Meeting.STATUS_RECORDING)
                .startedAt(begin)
                .chunkSec(chunkSec == null ? config.getChunkSec() : clamp(chunkSec, 5, 120))
                .codec(normalizeCodec(codec))
                .sampleRate(sampleRate == null ? 16000 : sampleRate)
                .audioExpiresAt(begin.plus(config.getRetentionDays(), ChronoUnit.DAYS))
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return meetings.save(meeting);
        } catch (org.springframework.dao.DataIntegrityViolationException concurrent) {
            // 同一 client_key 并发 start：唯一键兜底，返回已有会议
            if (key != null) {
                return meetings.findByDeviceIdAndClientKey(device.getDeviceId(), key)
                        .orElseThrow(() -> concurrent);
            }
            throw concurrent;
        }
    }

    public void uploadChunk(Device device, String meetingId, int seq, byte[] data, String shaHeader) {
        Meeting meeting = requireDeviceMeeting(device, meetingId);
        uploadChunkInternal(meeting, seq, data, shaHeader);
    }

    /**
     * 音频导入（后台）：WAV → 16k PCM → 按 30 秒切片 → 走与设备完全相同的上传/结束流程。
     * 无麦克风（HTTP 环境）或会议系统录音文件可用此入口。
     */
    public Meeting importPcm(Device device, String title, byte[] pcm16) {
        if (pcm16 == null || pcm16.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty audio");
        }
        Meeting meeting = start(device, null, Instant.now(), "pcm16", 16000, config.getChunkSec(), title);
        int chunkBytes = meeting.getChunkSec() * 16000 * 2;
        int seq = 0;
        for (int off = 0; off < pcm16.length; off += chunkBytes) {
            int len = Math.min(chunkBytes, pcm16.length - off);
            uploadChunkInternal(meeting, seq++, java.util.Arrays.copyOfRange(pcm16, off, off + len), null);
        }
        return endInternal(meeting, seq - 1);
    }

    private void uploadChunkInternal(Meeting meeting, int seq, byte[] data, String shaHeader) {
        String meetingId = meeting.getMeetingId();
        if (seq < 0 || seq > MAX_SEQ) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid seq");
        }
        if (data == null || data.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty audio chunk");
        }
        if (data.length > config.getMaxChunkBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "audio chunk too large");
        }
        if (!Meeting.STATUS_RECORDING.equals(meeting.getStatus())
                && !Meeting.STATUS_UPLOADING.equals(meeting.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "meeting is not accepting chunks");
        }
        String sha = sha256(data);
        if (shaHeader != null && !shaHeader.isBlank() && !shaHeader.equalsIgnoreCase(sha)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sha256 mismatch");
        }
        Optional<MeetingChunk> existing = chunks.findByMeetingIdAndSeq(meetingId, seq);
        if (existing.isPresent()) {
            MeetingChunk c = existing.get();
            if (!c.getSizeBytes().equals((long) data.length)
                    || (c.getSha256() != null && !c.getSha256().equalsIgnoreCase(sha))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "chunk already exists with different content");
            }
            return; // 幂等
        }

        String storageKey = audioStore.write(meeting.getCustomerId(), meetingId, seq, meeting.getCodec(), data);
        try {
            chunks.save(MeetingChunk.builder()
                    .meetingId(meetingId)
                    .seq(seq)
                    .sizeBytes((long) data.length)
                    .sha256(sha)
                    .storageKey(storageKey)
                    .receivedAt(Instant.now())
                    .build());
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // 并发重复上传：唯一键兜底，按幂等处理
            return;
        }
        if (meeting.getLastSeq() == null || seq > meeting.getLastSeq()) {
            meeting.setLastSeq(seq);
        }
        meeting.setStatus(Meeting.STATUS_UPLOADING);
        meeting.setUpdatedAt(Instant.now());
        meetings.save(meeting);
    }

    public Meeting end(Device device, String meetingId, Integer lastSeq) {
        Meeting meeting = requireDeviceMeeting(device, meetingId);
        return endInternal(meeting, lastSeq);
    }

    /** 结束并触发异步处理；重复调用幂等（已在处理/已完成的会议原样返回）。 */
    public Meeting endInternal(Meeting meeting, Integer lastSeq) {
        if (Meeting.STATUS_DRAFT.equals(meeting.getStatus())
                || Meeting.STATUS_REVIEWED.equals(meeting.getStatus())
                || Meeting.STATUS_DRAFTING.equals(meeting.getStatus())
                || Meeting.STATUS_TRANSCRIBING.equals(meeting.getStatus())) {
            return meeting;
        }
        int expected = lastSeq != null ? lastSeq
                : (meeting.getLastSeq() == null ? -1 : meeting.getLastSeq());
        if (expected < -1 || expected > MAX_SEQ) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid last_seq");
        }
        meeting.setExpectedLastSeq(expected);
        meeting.setEndedAt(meeting.getEndedAt() == null ? Instant.now() : meeting.getEndedAt());
        meeting.setDurationSec(Math.max(0, expected + 1) * meeting.getChunkSec());
        meeting.setMissingSeqs(writeJson(missingSeqs(meeting.getMeetingId(), expected)));
        meeting.setStatus(Meeting.STATUS_TRANSCRIBING);
        meeting.setErrorMessage(null);
        meeting.setUpdatedAt(Instant.now());
        Meeting saved = meetings.save(meeting);
        processor.process(saved.getId());
        return saved;
    }

    // ===================== 后台侧 =====================

    public List<Meeting> list(Long customerId, Long storeId, String status) {
        if (status == null || status.isBlank()) {
            return meetings.findByCustomerIdAndStoreIdOrderByStartedAtDesc(customerId, storeId);
        }
        return meetings.findByCustomerIdAndStoreIdAndStatusOrderByStartedAtDesc(customerId, storeId, status);
    }

    public Meeting findForTenant(Long customerId, Long storeId, String meetingId) {
        return meetings.findByMeetingIdAndCustomerIdAndStoreId(meetingId, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "meeting not found"));
    }

    @Transactional
    public Meeting updateMinutes(Meeting meeting, String title, Map<String, Object> minutesJson,
                                 List<ActionEdit> edits) {
        if (title != null && !title.isBlank()) {
            meeting.setTitle(title.trim());
        }
        if (minutesJson != null) {
            if (title != null && !title.isBlank()) {
                minutesJson.put("title", title.trim());
            }
            if (edits != null) {
                minutesJson.put("actions", edits.stream().map(e -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("task", e.task());
                    a.put("owner", e.owner());
                    a.put("due", e.dueDate());
                    a.put("ref", e.sourceRef());
                    return a;
                }).toList());
            }
            meeting.setMinutesJson(writeJson(minutesJson));
        }
        if (edits != null) {
            actionRepo.deleteByMeetingId(meeting.getMeetingId());
            Instant now = Instant.now();
            int order = 0;
            for (ActionEdit e : edits) {
                if (e.task() == null || e.task().isBlank()) {
                    continue;
                }
                actionRepo.save(MeetingActionItem.builder()
                        .meetingId(meeting.getMeetingId())
                        .customerId(meeting.getCustomerId())
                        .storeId(meeting.getStoreId())
                        .task(e.task().trim())
                        .owner(blankToConfirm(e.owner()))
                        .dueDate(blankToConfirm(e.dueDate()))
                        .status(normalizeActionStatus(e.status()))
                        .sourceRef(e.sourceRef())
                        .sortOrder(order++)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
        }
        // 人工修改后回到待审核，避免未经确认的修改直接进入周报
        meeting.setStatus(Meeting.STATUS_DRAFT);
        meeting.setUpdatedAt(Instant.now());
        return meetings.save(meeting);
    }

    public Meeting approve(Meeting meeting) {
        if (meeting.getMinutesJson() == null || meeting.getMinutesJson().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "minutes not generated yet");
        }
        meeting.setStatus(Meeting.STATUS_REVIEWED);
        meeting.setUpdatedAt(Instant.now());
        return meetings.save(meeting);
    }

    public Meeting regenerate(Meeting meeting) {
        if (meeting.getAudioDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "audio already deleted");
        }
        meeting.setStatus(Meeting.STATUS_TRANSCRIBING);
        meeting.setErrorMessage(null);
        meeting.setUpdatedAt(Instant.now());
        Meeting saved = meetings.save(meeting);
        processor.process(saved.getId());
        return saved;
    }

    public byte[] exportDocx(Meeting meeting) {
        if (meeting.getMinutesJson() == null || meeting.getMinutesJson().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "minutes not generated yet");
        }
        try {
            Map<String, Object> json = mapper.readValue(meeting.getMinutesJson(),
                    new TypeReference<Map<String, Object>>() {});
            return minutes.renderDocx(meeting, json, actionRepo.findByMeetingIdOrderBySortOrderAsc(meeting.getMeetingId()));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "docx render failed", e);
        }
    }

    public byte[] audio(Meeting meeting) {
        if (meeting.getAudioDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "audio already deleted");
        }
        List<MeetingChunk> parts = chunks.findByMeetingIdOrderBySeqAsc(meeting.getMeetingId());
        if (parts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no audio");
        }
        return audioStore.concat(meeting, parts, meeting.getSampleRate());
    }

    public List<Integer> missingList(Meeting meeting) {
        if (meeting.getMissingSeqs() == null || meeting.getMissingSeqs().isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(meeting.getMissingSeqs(), new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<MeetingActionItem> actions(String meetingId) {
        return actionRepo.findByMeetingIdOrderBySortOrderAsc(meetingId);
    }

    public MeetingActionItem updateActionStatus(Long customerId, Long storeId, Long actionId, String status) {
        MeetingActionItem item = actionRepo.findById(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "action not found"));
        if (!item.getCustomerId().equals(customerId) || !item.getStoreId().equals(storeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "action not found");
        }
        item.setStatus(normalizeActionStatus(status));
        item.setUpdatedAt(Instant.now());
        return actionRepo.save(item);
    }

    // ===================== 定时任务 =====================

    /** 超时未结束的会议自动 finalize（设备丢失/断电兜底）。 */
    public int finalizeStale() {
        Instant threshold = Instant.now().minus(config.getFinalizeAfterMinutes(), ChronoUnit.MINUTES);
        List<Meeting> stale = meetings.findByStatusInAndUpdatedAtBefore(
                List.of(Meeting.STATUS_RECORDING, Meeting.STATUS_UPLOADING), threshold);
        int count = 0;
        for (Meeting m : stale) {
            try {
                endInternal(m, m.getLastSeq());
                count++;
            } catch (Exception ignored) {
                // 单条失败不影响其它会议
            }
        }
        return count;
    }

    /** 到期删除音频文件（保留转写与纪要）。 */
    public int cleanupExpiredAudio() {
        List<Meeting> expired = meetings.findByAudioExpiresAtBeforeAndAudioDeletedAtIsNull(Instant.now());
        int count = 0;
        for (Meeting m : expired) {
            try {
                audioStore.deleteMeeting(m.getCustomerId(), m.getMeetingId());
                m.setAudioDeletedAt(Instant.now());
                m.setUpdatedAt(Instant.now());
                meetings.save(m);
                count++;
            } catch (Exception ignored) {
                // 单条失败下次再试
            }
        }
        return count;
    }

    // ===================== 内部 =====================

    private Meeting requireDeviceMeeting(Device device, String meetingId) {
        Meeting meeting = meetings.findByMeetingId(meetingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "meeting not found"));
        if (!meeting.getDeviceId().equals(device.getDeviceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "meeting not found");
        }
        return meeting;
    }

    private List<Integer> missingSeqs(String meetingId, int expectedLastSeq) {
        if (expectedLastSeq < 0) {
            return List.of();
        }
        Set<Integer> got = new HashSet<>();
        for (MeetingChunk c : chunks.findByMeetingIdOrderBySeqAsc(meetingId)) {
            got.add(c.getSeq());
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i <= expectedLastSeq; i++) {
            if (!got.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String blankToConfirm(String s) {
        return s == null || s.isBlank() ? "要確認" : s.trim();
    }

    private static String normalizeCodec(String codec) {
        String c = codec == null ? "pcm16" : codec.toLowerCase(Locale.ROOT);
        return switch (c) {
            case "pcm16", "opus", "adpcm" -> c;
            default -> "pcm16";
        };
    }

    private static String normalizeActionStatus(String status) {
        if (status == null) {
            return MeetingActionItem.STATUS_TODO;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case MeetingActionItem.STATUS_DOING -> MeetingActionItem.STATUS_DOING;
            case MeetingActionItem.STATUS_DONE -> MeetingActionItem.STATUS_DONE;
            case MeetingActionItem.STATUS_CANCELLED -> MeetingActionItem.STATUS_CANCELLED;
            default -> MeetingActionItem.STATUS_TODO;
        };
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
