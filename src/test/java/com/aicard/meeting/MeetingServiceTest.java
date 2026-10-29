package com.aicard.meeting;

import com.aicard.common.domain.Device;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MeetingServiceTest {

    private MeetingRepository meetings;
    private MeetingChunkRepository chunks;
    private MeetingActionItemRepository actionRepo;
    private MeetingAudioStore audioStore;
    private MeetingConfig config;
    private MeetingProcessor processor;
    private MinutesService minutes;
    private MeetingService service;

    @BeforeEach
    void setUp() {
        meetings = mock(MeetingRepository.class);
        chunks = mock(MeetingChunkRepository.class);
        actionRepo = mock(MeetingActionItemRepository.class);
        audioStore = mock(MeetingAudioStore.class);
        config = mock(MeetingConfig.class);
        processor = mock(MeetingProcessor.class);
        minutes = mock(MinutesService.class);
        when(config.getChunkSec()).thenReturn(30);
        when(config.getRetentionDays()).thenReturn(30);
        when(config.getMaxChunkBytes()).thenReturn(2_097_152);
        when(meetings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new MeetingService(meetings, chunks, actionRepo, audioStore, config, processor, minutes,
                new ObjectMapper());
    }

    @Test
    void startCreatesRecordingAndIsIdempotentByClientKey() {
        Device device = device();
        Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        Meeting m = service.start(device, "key-1", startedAt, "pcm16", 16000, 30, "定例会議");

        assertThat(m.getMeetingId()).startsWith("mtg-");
        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_RECORDING);
        assertThat(m.getCustomerId()).isEqualTo(7L);
        assertThat(m.getStoreId()).isEqualTo(8L);
        assertThat(m.getStaffName()).isEqualTo("田中");
        assertThat(m.getAudioExpiresAt()).isEqualTo(startedAt.plus(30, ChronoUnit.DAYS));

        when(meetings.findByDeviceIdAndClientKey("dev-1", "key-1")).thenReturn(Optional.of(m));
        Meeting again = service.start(device, "key-1", null, null, null, null, null);
        assertThat(again.getMeetingId()).isEqualTo(m.getMeetingId());
    }

    @Test
    void startClampsChunkSecToProtocolRange() {
        Device device = device();
        Meeting high = service.start(device, "key-60", Instant.now(), "pcm16", 16000, 60, "定例会議");
        Meeting low = service.start(device, "key-1", Instant.now(), "pcm16", 16000, 1, "定例会議");

        assertThat(high.getChunkSec()).isEqualTo(30);
        assertThat(low.getChunkSec()).isEqualTo(5);
    }

    @Test
    void uploadChunkIsIdempotentAndUpdatesLastSeq() {
        Device device = device();
        Meeting m = meeting();
        when(meetings.findByMeetingId("mtg-1")).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdAndSeq("mtg-1", 3)).thenReturn(Optional.empty());
        when(audioStore.write(7L, "mtg-1", 3, "pcm16", new byte[]{1, 2, 3})).thenReturn("7/mtg-1/00000003.pcm");

        service.uploadChunk(device, "mtg-1", 3, new byte[]{1, 2, 3}, null);

        assertThat(m.getLastSeq()).isEqualTo(3);
        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_UPLOADING);
        verify(chunks).save(any(MeetingChunk.class));

        // 幂等：相同内容重复上传不再写文件
        MeetingChunk existing = MeetingChunk.builder().meetingId("mtg-1").seq(3)
                .sizeBytes(3L).sha256(sha256(new byte[]{1, 2, 3})).storageKey("7/mtg-1/00000003.pcm").build();
        when(chunks.findByMeetingIdAndSeq("mtg-1", 3)).thenReturn(Optional.of(existing));
        service.uploadChunk(device, "mtg-1", 3, new byte[]{1, 2, 3}, null);
        verify(audioStore, times(1)).write(any(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void uploadChunkRejectsShaMismatch() {
        Device device = device();
        when(meetings.findByMeetingId("mtg-1")).thenReturn(Optional.of(meeting()));
        assertThatThrownBy(() -> service.uploadChunk(device, "mtg-1", 0, new byte[]{1}, "deadbeef"))
                .isInstanceOf(MeetingException.class)
                .hasMessageContaining("sha256 mismatch")
                .extracting(e -> ((MeetingException) e).getErrorCode())
                .isEqualTo("E_MEETING_SHA_MISMATCH");
    }

    @Test
    void endWithMissingSeqsKeepsUploadingAndDoesNotProcess() {
        Device device = device();
        Meeting m = meeting();
        when(meetings.findByMeetingId("mtg-1")).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("mtg-1")).thenReturn(List.of(
                MeetingChunk.builder().meetingId("mtg-1").seq(0).build(),
                MeetingChunk.builder().meetingId("mtg-1").seq(2).build()));

        service.end(device, "mtg-1", 3);

        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_UPLOADING);
        assertThat(m.getExpectedLastSeq()).isEqualTo(3);
        assertThat(m.getDurationSec()).isEqualTo(120);
        assertThat(service.missingList(m)).containsExactly(1, 3);
        verify(processor, never()).process(any());
    }

    @Test
    void uploadingMissingChunksAutoTranscribesWhenComplete() {
        Device device = device();
        Meeting m = meeting();
        m.setStatus(Meeting.STATUS_UPLOADING);
        m.setExpectedLastSeq(3);
        m.setLastSeq(2);
        m.setMissingSeqs("[1,3]");
        when(meetings.findByMeetingId("mtg-1")).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("mtg-1"))
                .thenReturn(List.of(chunk(0), chunk(1), chunk(2)))
                .thenReturn(List.of(chunk(0), chunk(1), chunk(2), chunk(3)));
        when(chunks.findByMeetingIdAndSeq(eq("mtg-1"), anyInt())).thenReturn(Optional.empty());
        when(audioStore.write(any(), anyString(), anyInt(), anyString(), any())).thenReturn("7/mtg-1/x.pcm");

        service.uploadChunk(device, "mtg-1", 1, new byte[]{1}, null);
        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_UPLOADING);
        assertThat(service.missingList(m)).containsExactly(3);
        verify(processor, never()).process(any());

        service.uploadChunk(device, "mtg-1", 3, new byte[]{1}, null);
        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_TRANSCRIBING);
        assertThat(service.missingList(m)).isEmpty();
        verify(processor, times(1)).process(m.getId());
    }

    @Test
    void uploadChunkRejectsSeqBeyondExpectedLastSeq() {
        Device device = device();
        Meeting m = meeting();
        m.setStatus(Meeting.STATUS_UPLOADING);
        m.setExpectedLastSeq(3);
        when(meetings.findByMeetingId("mtg-1")).thenReturn(Optional.of(m));

        assertThatThrownBy(() -> service.uploadChunk(device, "mtg-1", 4, new byte[]{1}, null))
                .isInstanceOf(MeetingException.class)
                .extracting(e -> ((MeetingException) e).getErrorCode())
                .isEqualTo("E_MEETING_STATE");
    }

    @Test
    void repeatedEndNeverShrinksExpectedLastSeq() {
        Device device = device();
        Meeting m = meeting();
        m.setStatus(Meeting.STATUS_UPLOADING);
        m.setExpectedLastSeq(5);
        m.setLastSeq(4);
        when(meetings.findByMeetingId("mtg-1")).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("mtg-1")).thenReturn(List.of(
                chunk(0), chunk(1), chunk(2), chunk(3), chunk(4)));

        service.end(device, "mtg-1", 2);

        assertThat(m.getExpectedLastSeq()).isEqualTo(5);
        assertThat(service.missingList(m)).containsExactly(5);
        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_UPLOADING);
    }

    @Test
    void finalizeStaleUsesDeclaredExpectedLastSeq() {
        Meeting m = meeting();
        m.setStatus(Meeting.STATUS_UPLOADING);
        m.setExpectedLastSeq(3);
        m.setLastSeq(2);
        when(meetings.findByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("mtg-1")).thenReturn(List.of(chunk(0), chunk(2)));

        assertThat(service.finalizeStale()).isEqualTo(1);

        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_TRANSCRIBING);
        assertThat(m.getExpectedLastSeq()).isEqualTo(3);
        assertThat(service.missingList(m)).containsExactly(1, 3);
        verify(processor).process(m.getId());
    }

    @Test
    void deviceCannotUploadToAnotherDeviceMeeting() {
        Device device = device();
        Meeting m = meeting();
        m.setDeviceId("other-device");
        when(meetings.findByMeetingId("mtg-1")).thenReturn(Optional.of(m));
        assertThatThrownBy(() -> service.uploadChunk(device, "mtg-1", 0, new byte[]{1}, null))
                .isInstanceOf(MeetingException.class)
                .extracting(e -> ((MeetingException) e).getErrorCode())
                .isEqualTo("E_MEETING_NOT_FOUND");
    }

    private static MeetingChunk chunk(int seq) {
        return MeetingChunk.builder().meetingId("mtg-1").seq(seq).build();
    }

    private static Device device() {
        return Device.builder().deviceId("dev-1").customerId(7L).storeId(8L)
                .staffName("田中").token("token").build();
    }

    private static Meeting meeting() {
        return Meeting.builder()
                .id(11L).meetingId("mtg-1").customerId(7L).storeId(8L).deviceId("dev-1").staffName("田中")
                .status(Meeting.STATUS_RECORDING).startedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .chunkSec(30).codec("pcm16").sampleRate(16000)
                .audioExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
