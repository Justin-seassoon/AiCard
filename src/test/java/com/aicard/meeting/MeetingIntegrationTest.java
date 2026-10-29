package com.aicard.meeting;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会议录音端到端（真跑 PostgreSQL 容器）：
 * start → 分片上传（幂等）→ end（完整性核对）→ 异步转写 → 结构化纪要 + 待办 → 审核 → 导出 → 到期清理。
 */
@SpringBootTest
@Testcontainers
class MeetingIntegrationTest {

    private static final Path STORAGE = tempDir();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("meeting.storage-dir", STORAGE::toString);
    }

    @Autowired MeetingService meetings;
    @Autowired MeetingRepository meetingRepo;
    @Autowired MeetingActionItemRepository actionRepo;
    @Autowired DeviceRepository devices;
    @Autowired JdbcTemplate jdbc;

    @Test
    void recordingToReviewedMinutes() throws Exception {
        Device device = devices.findByDeviceId("demo-device-001").orElseThrow();
        Meeting m = meetings.start(device, "it-key-" + System.nanoTime(), Instant.now(),
                "pcm16", 16000, 30, "テスト定例会議");

        // 两片（幂等：第 0 片重复上传一次）
        meetings.uploadChunk(device, m.getMeetingId(), 0, new byte[3200], null);
        meetings.uploadChunk(device, m.getMeetingId(), 0, new byte[3200], null);
        meetings.uploadChunk(device, m.getMeetingId(), 1, new byte[3200], null);

        meetings.end(device, m.getMeetingId(), 1);
        Meeting done = await(m.getMeetingId());

        assertThat(done.getStatus()).as("error=" + done.getErrorMessage()).isEqualTo(Meeting.STATUS_DRAFT);
        assertThat(done.getTranscript()).contains("定例会議").contains("経費精算");
        assertThat(done.getMinutesJson()).contains("定例会議").contains("経費精算");
        assertThat(actionRepo.findByMeetingIdOrderBySortOrderAsc(m.getMeetingId())).hasSize(3);

        Meeting reviewed = meetings.approve(done);
        assertThat(reviewed.getStatus()).isEqualTo(Meeting.STATUS_REVIEWED);

        byte[] docx = meetings.exportDocx(reviewed);
        assertThat(docx.length).isGreaterThan(1000);
        assertThat(new String(docx, 0, 2)).isEqualTo("PK");
    }

    @Test
    void missingChunksKeepUploadingThenResumeOnFill() throws Exception {
        Device device = devices.findByDeviceId("demo-device-001").orElseThrow();
        Meeting m = meetings.start(device, "it-missing-" + System.nanoTime(), Instant.now(),
                "pcm16", 16000, 30, null);
        meetings.uploadChunk(device, m.getMeetingId(), 0, new byte[3200], null);
        meetings.uploadChunk(device, m.getMeetingId(), 2, new byte[3200], null);

        meetings.end(device, m.getMeetingId(), 3);
        Meeting waiting = meetingRepo.findByMeetingId(m.getMeetingId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(Meeting.STATUS_UPLOADING);
        assertThat(waiting.getExpectedLastSeq()).isEqualTo(3);
        assertThat(meetings.missingList(waiting)).containsExactly(1, 3);

        // 补片后自动转写（无需再次 end）
        meetings.uploadChunk(device, m.getMeetingId(), 1, new byte[3200], null);
        meetings.uploadChunk(device, m.getMeetingId(), 3, new byte[3200], null);
        Meeting done = await(m.getMeetingId());

        assertThat(done.getStatus()).isEqualTo(Meeting.STATUS_DRAFT);
        assertThat(meetings.missingList(done)).containsExactly(1, 3);

        // 到期清理：音频删除，转写/纪要保留
        jdbc.update("UPDATE meeting SET audio_expires_at = now() - interval '1 day' WHERE meeting_id = ?",
                m.getMeetingId());
        assertThat(meetings.cleanupExpiredAudio()).isGreaterThanOrEqualTo(1);

        Meeting after = meetingRepo.findByMeetingId(m.getMeetingId()).orElseThrow();
        assertThat(after.getAudioDeletedAt()).isNotNull();
        assertThat(after.getTranscript()).isNotBlank();
        assertThat(after.getMinutesJson()).isNotBlank();
        assertThatThrownBy(() -> meetings.audio(after))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("audio already deleted");
    }

    @Test
    void staleUploadingMeetingFinalizesWithDeclaredExpected() throws Exception {
        Device device = devices.findByDeviceId("demo-device-001").orElseThrow();
        Meeting m = meetings.start(device, "it-stale-" + System.nanoTime(), Instant.now(),
                "pcm16", 16000, 30, null);
        meetings.uploadChunk(device, m.getMeetingId(), 0, new byte[3200], null);
        meetings.end(device, m.getMeetingId(), 2);

        assertThat(meetingRepo.findByMeetingId(m.getMeetingId()).orElseThrow().getStatus())
                .isEqualTo(Meeting.STATUS_UPLOADING);

        // 回拨 updated_at，模拟 10 分钟无新分片
        jdbc.update("UPDATE meeting SET updated_at = now() - interval '11 minutes' WHERE meeting_id = ?",
                m.getMeetingId());
        assertThat(meetings.finalizeStale()).isGreaterThanOrEqualTo(1);

        Meeting done = await(m.getMeetingId());
        assertThat(done.getStatus()).isEqualTo(Meeting.STATUS_DRAFT);
        assertThat(done.getExpectedLastSeq()).isEqualTo(2);
        assertThat(meetings.missingList(done)).containsExactly(1, 2);
    }

    @Test
    void importWavRunsSamePipeline() throws Exception {
        Device device = devices.findByDeviceId("demo-device-001").orElseThrow();
        byte[] pcm = WavPcm.parse(WavPcmTest.wav(16000, 1, 3, 1000)).pcm16();

        Meeting m = meetings.importPcm(device, "導入テスト", pcm);
        Meeting done = await(m.getMeetingId());

        assertThat(done.getStatus()).as("error=" + done.getErrorMessage()).isEqualTo(Meeting.STATUS_DRAFT);
        assertThat(done.getTitle()).isEqualTo("導入テスト");
        assertThat(done.getTranscript()).contains("定例会議");
        assertThat(actionRepo.findByMeetingIdOrderBySortOrderAsc(m.getMeetingId())).hasSize(3);
    }

    private Meeting await(String meetingId) throws InterruptedException {
        Set<String> terminal = Set.of(Meeting.STATUS_DRAFT, Meeting.STATUS_REVIEWED, Meeting.STATUS_FAILED);
        for (int i = 0; i < 150; i++) {
            Meeting m = meetingRepo.findByMeetingId(meetingId).orElseThrow();
            if (terminal.contains(m.getStatus())) {
                return m;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("meeting processing timed out: " + meetingId);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("aicard-meeting-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
