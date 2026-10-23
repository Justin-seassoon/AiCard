package com.aicard.meeting;

import com.aicard.common.domain.Device;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
        Meeting m = service.start(device, "key-1", Instant.parse("2026-10-23T01:00:00Z"),
                "pcm16", 16000, 30, "定例会議");

        assertThat(m.getMeetingId()).startsWith("mtg-");
        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_RECORDING);
        assertThat(m.getCustomerId()).isEqualTo(7L);
        assertThat(m.getStoreId()).isEqualTo(8L);
        assertThat(m.getStaffName()).isEqualTo("田中");
        assertThat(m.getAudioExpiresAt()).isEqualTo(Instant.parse("2026-11-22T01:00:00Z"));

        when(meetings.findByDeviceIdAndClientKey("dev-1", "key-1")).thenReturn(Optional.of(m));
        Meeting again = service.start(device, "key-1", null, null, null, null, null);
        assertThat(again.getMeetingId()).isEqualTo(m.getMeetingId());
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
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("sha256 mismatch");
    }

    @Test
    void endComputesMissingSeqsAndTriggersProcessing() {
        Device device = device();
        Meeting m = meeting();
        when(meetings.findByMeetingId("mtg-1")).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("mtg-1")).thenReturn(List.of(
                MeetingChunk.builder().meetingId("mtg-1").seq(0).build(),
                MeetingChunk.builder().meetingId("mtg-1").seq(2).build()));

        service.end(device, "mtg-1", 3);

        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_TRANSCRIBING);
        assertThat(m.getDurationSec()).isEqualTo(120);
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
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("meeting not found");
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
