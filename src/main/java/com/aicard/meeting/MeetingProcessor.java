package com.aicard.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 会议后处理（异步）：转写 → 结构化纪要 → 待办落库。
 * <p>租户从会议行显式取（C5），不依赖 TenantContext；失败只影响本会议，状态置 {@code failed}。</p>
 */
@Component
public class MeetingProcessor {

    private static final Logger log = LoggerFactory.getLogger(MeetingProcessor.class);

    private final MeetingRepository meetings;
    private final MeetingChunkRepository chunks;
    private final MeetingTranscriptionService transcription;
    private final MinutesService minutes;
    private final MeetingActionStore actions;
    private final ObjectMapper mapper;

    public MeetingProcessor(MeetingRepository meetings, MeetingChunkRepository chunks,
                            MeetingTranscriptionService transcription, MinutesService minutes,
                            MeetingActionStore actions, ObjectMapper mapper) {
        this.meetings = meetings;
        this.chunks = chunks;
        this.transcription = transcription;
        this.minutes = minutes;
        this.actions = actions;
        this.mapper = mapper;
    }

    @Async
    public void process(Long id) {
        Meeting meeting = meetings.findById(id).orElse(null);
        if (meeting == null || meeting.isReviewed()) {
            return;
        }
        try {
            List<MeetingChunk> parts = chunks.findByMeetingIdOrderBySeqAsc(meeting.getMeetingId());
            if (parts.isEmpty()) {
                fail(meeting, "録音データがありません");
                return;
            }

            meeting.setStatus(Meeting.STATUS_TRANSCRIBING);
            touch(meeting);
            meetings.save(meeting);

            MeetingTranscript transcript = transcription.transcribe(meeting, parts);
            if (transcript.text() == null || transcript.text().isBlank()) {
                fail(meeting, "転写結果が空です");
                return;
            }
            meeting.setTranscript(transcript.text());
            meeting.setTranscriptJson(mapper.writeValueAsString(transcript.segments()));

            meeting.setStatus(Meeting.STATUS_DRAFTING);
            touch(meeting);
            meetings.save(meeting);

            MinutesService.Result result = minutes.generate(meeting, transcript);
            meeting.setMinutesJson(result.json());
            meeting.setStatus(Meeting.STATUS_DRAFT);
            meeting.setErrorMessage(null);
            touch(meeting);
            meetings.save(meeting);

            actions.replace(meeting, result.actions());
            log.info("meeting processed: {} actions={}", meeting.getMeetingId(), result.actions().size());
        } catch (Exception e) {
            log.warn("meeting processing failed: {}", meeting.getMeetingId(), e);
            fail(meeting, e.getMessage());
        }
    }

    private void fail(Meeting meeting, String message) {
        meeting.setStatus(Meeting.STATUS_FAILED);
        meeting.setErrorMessage(message == null ? "処理に失敗しました" : message);
        touch(meeting);
        meetings.save(meeting);
    }

    private static void touch(Meeting m) {
        m.setUpdatedAt(Instant.now());
    }
}
