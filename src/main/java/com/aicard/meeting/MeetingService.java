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

    /** 设备 start 上报的固件 / 能力 / 触发 / 合规留痕。后台 WAV 导入传 null。 */
    public record DeviceMeta(String fwVersion, List<String> features, String trigger,
                             Boolean noticePlayed, String noticeVersion) {}

    // ===================== 设备侧 =====================

    public Meeting start(Device device, String clientKey, Instant startedAt, String codec,
                         Integer sampleRate, Integer chunkSec, String title) {
        return start(device, clientKey, startedAt, codec, sampleRate, chunkSec, title, null);
    }

    public Meeting start(Device device, String clientKey, Instant startedAt, String codec,
                         Integer sampleRate, Integer chunkSec, String title, DeviceMeta meta) {
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
                .fwVersion(meta == null ? null : blankToNull(meta.fwVersion()))
                .features(meta == null || meta.features() == null ? null : writeJson(meta.features()))
                .triggerType(meta == null ? null : blankToNull(meta.trigger()))
                .noticePlayed(meta == null ? null : meta.noticePlayed())
                .noticeVersion(meta == null ? null : blankToNull(meta.noticeVersion()))
                .status(Meeting.STATUS_RECORDING)
                .startedAt(begin)
                .chunkSec(chunkSec == null ? config.getChunkSec() : clamp(chunkSec, 5, 30))
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
        try {
            for (int off = 0; off < pcm16.length; off += chunkBytes) {
                int len = Math.min(chunkBytes, pcm16.length - off);
                uploadChunkInternal(meeting, seq++, java.util.Arrays.copyOfRange(pcm16, off, off + len), null);
            }
            return endInternal(meeting, seq - 1);
        } catch (MeetingException e) {
            // 后台导入沿用 Spring 默认错误格式，不暴露设备错误信封
            throw new ResponseStatusException(e.getStatus(), e.getMessage(), e);
        }
    }

    private void uploadChunkInternal(Meeting meeting, int seq, byte[] data, String shaHeader) {
        String meetingId = meeting.getMeetingId();
        if (meeting.getAudioDeletedAt() != null) {
            throw new MeetingException(HttpStatus.GONE, "E_MEETING_EXPIRED", "audio expired", false);
        }
        if (seq < 0 || seq > MAX_SEQ) {
            throw MeetingException.invalid("invalid seq");
        }
        if (data == null || data.length == 0) {
            throw MeetingException.invalid("empty audio chunk");
        }
        if (data.length > config.getMaxChunkBytes()) {
            throw new MeetingException(HttpStatus.PAYLOAD_TOO_LARGE, "E_MEETING_TOO_LARGE",
                    "audio chunk too large", false);
        }
        if (!Meeting.STATUS_RECORDING.equals(meeting.getStatus())
                && !Meeting.STATUS_UPLOADING.equals(meeting.getStatus())) {
            throw MeetingException.state("meeting is not accepting chunks");
        }
        if (meeting.getExpectedLastSeq() != null && seq > meeting.getExpectedLastSeq()) {
            throw MeetingException.state("seq exceeds expected_last_seq");
        }
        String sha = sha256(data);
        if (shaHeader != null && !shaHeader.isBlank() && !shaHeader.equalsIgnoreCase(sha)) {
            throw new MeetingException(HttpStatus.BAD_REQUEST, "E_MEETING_SHA_MISMATCH",
                    "sha256 mismatch", true);
        }
        Optional<MeetingChunk> existing = chunks.findByMeetingIdAndSeq(meetingId, seq);
        if (existing.isPresent()) {
            MeetingChunk c = existing.get();
            if (!c.getSizeBytes().equals((long) data.length)
                    || (c.getSha256() != null && !c.getSha256().equalsIgnoreCase(sha))) {
                throw new MeetingException(HttpStatus.CONFLICT, "E_MEETING_SEQ_CONFLICT",
                        "chunk already exists with different content", false);
            }
            afterChunkStored(meeting);
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
            afterChunkStored(meeting);
            return;
        }
        if (meeting.getLastSeq() == null || seq > meeting.getLastSeq()) {
            meeting.setLastSeq(seq);
        }
        meeting.setStatus(Meeting.STATUS_UPLOADING);
        meeting.setUpdatedAt(Instant.now());
        afterChunkStored(meeting);
    }

    /** 分片落库后：已声明 last_seq 时重算缺片，补齐则直接转写（设备无需再次 end 也能自动收尾）。 */
    private void afterChunkStored(Meeting meeting) {
        if (meeting.getExpectedLastSeq() == null) {
            meetings.save(meeting);
            return;
        }
        List<Integer> missing = missingSeqs(meeting.getMeetingId(), meeting.getExpectedLastSeq());
        meeting.setMissingSeqs(writeJson(missing));
        meeting.setUpdatedAt(Instant.now());
        if (!missing.isEmpty()) {
            meeting.setStatus(Meeting.STATUS_UPLOADING);
            meetings.save(meeting);
            return;
        }
        meeting.setStatus(Meeting.STATUS_TRANSCRIBING);
        meeting.setErrorMessage(null);
        Meeting saved = meetings.save(meeting);
        processor.process(saved.getId());
    }

    public Meeting end(Device device, String meetingId, Integer lastSeq) {
        if (lastSeq == null) {
            throw MeetingException.invalid("last_seq is required");
        }
        Meeting meeting = requireDeviceMeeting(device, meetingId);
        return endInternal(meeting, lastSeq);
    }

    /**
     * 结束并核对完整性：无缺片 → 转写；有缺片 → 保持 uploading，等待设备补片（10 分钟无新片自动收尾）。
     * 重复调用幂等；已在处理/已完成的会议原样返回。
     */
    public Meeting endInternal(Meeting meeting, Integer lastSeq) {
        if (isProcessingOrDone(meeting.getStatus())) {
            return meeting;
        }
        if (lastSeq != null && (lastSeq < -1 || lastSeq > MAX_SEQ)) {
            throw MeetingException.invalid("invalid last_seq");
        }
        int expected = resolveExpected(meeting, lastSeq);
        applyEnd(meeting, expected);
        List<Integer> missing = missingSeqs(meeting.getMeetingId(), expected);
        meeting.setMissingSeqs(writeJson(missing));
        if (missing.isEmpty()) {
            return transcribe(meeting);
        }
        meeting.setStatus(Meeting.STATUS_UPLOADING);
        meeting.setUpdatedAt(Instant.now());
        return meetings.save(meeting);
    }

    public Meeting statusForDevice(Device device, String meetingId) {
        return requireDeviceMeeting(device, meetingId);
    }

    public long uploadedCount(String meetingId) {
        return chunks.countByMeetingId(meetingId);
    }

    private static boolean isProcessingOrDone(String status) {
        return Meeting.STATUS_DRAFT.equals(status)
                || Meeting.STATUS_REVIEWED.equals(status)
                || Meeting.STATUS_DRAFTING.equals(status)
                || Meeting.STATUS_TRANSCRIBING.equals(status);
    }

    /** expected_last_seq 取「已记录值 / 已收到最大 seq / 本次 last_seq」三者最大，不允许减小。 */
    private static int resolveExpected(Meeting meeting, Integer lastSeq) {
        int expected = -1;
        if (meeting.getExpectedLastSeq() != null) {
            expected = meeting.getExpectedLastSeq();
        }
        if (meeting.getLastSeq() != null) {
            expected = Math.max(expected, meeting.getLastSeq());
        }
        if (lastSeq != null) {
            expected = Math.max(expected, lastSeq);
        }
        return expected;
    }

    private static void applyEnd(Meeting meeting, int expected) {
        meeting.setExpectedLastSeq(expected);
        meeting.setEndedAt(meeting.getEndedAt() == null ? Instant.now() : meeting.getEndedAt());
        meeting.setDurationSec(Math.max(0, expected + 1) * meeting.getChunkSec());
    }

    private Meeting transcribe(Meeting meeting) {
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
                // 超时收尾按已声明的 expected_last_seq 收尾，保留缺片记录；未声明则按已收到最大 seq
                int expected = m.getExpectedLastSeq() != null ? m.getExpectedLastSeq()
                        : (m.getLastSeq() == null ? -1 : m.getLastSeq());
                applyEnd(m, expected);
                m.setMissingSeqs(writeJson(missingSeqs(m.getMeetingId(), expected)));
                transcribe(m);
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
                .orElseThrow(MeetingException::notFound);
        if (!meeting.getDeviceId().equals(device.getDeviceId())) {
            throw MeetingException.notFound();
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
