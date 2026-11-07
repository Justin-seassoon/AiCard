package com.aicard.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingProcessorTest {

    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final MeetingChunkRepository chunks = mock(MeetingChunkRepository.class);
    private final MeetingTranscriptionService transcription = mock(MeetingTranscriptionService.class);
    private final MinutesService minutes = mock(MinutesService.class);
    private final MeetingActionStore actions = mock(MeetingActionStore.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final MeetingProcessor processor =
            new MeetingProcessor(meetings, chunks, transcription, minutes, actions, mapper);

    private static Meeting meeting(String status) {
        return Meeting.builder().id(1L).meetingId("m1").customerId(1L).storeId(2L)
                .status(status).chunkSec(15).build();
    }

    private static MeetingChunk chunk() {
        return MeetingChunk.builder().meetingId("m1").seq(0).storageKey("k0").build();
    }

    @Test
    void doesNothingWhenMeetingMissing() {
        when(meetings.findById(1L)).thenReturn(Optional.empty());

        processor.process(1L);

        verify(chunks, never()).findByMeetingIdOrderBySeqAsc(any());
    }

    @Test
    void doesNothingWhenAlreadyReviewed() {
        when(meetings.findById(1L)).thenReturn(Optional.of(meeting(Meeting.STATUS_REVIEWED)));

        processor.process(1L);

        verify(chunks, never()).findByMeetingIdOrderBySeqAsc(any());
    }

    @Test
    void failsWhenNoChunks() {
        Meeting m = meeting(Meeting.STATUS_UPLOADING);
        when(meetings.findById(1L)).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("m1")).thenReturn(List.of());

        processor.process(1L);

        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_FAILED);
        assertThat(m.getErrorMessage()).isEqualTo("録音データがありません");
    }

    @Test
    void failsWhenTranscriptBlank() {
        Meeting m = meeting(Meeting.STATUS_UPLOADING);
        when(meetings.findById(1L)).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("m1")).thenReturn(List.of(chunk()));
        when(transcription.transcribe(any(), any())).thenReturn(MeetingTranscript.empty());

        processor.process(1L);

        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_FAILED);
        assertThat(m.getErrorMessage()).isEqualTo("転写結果が空です");
    }

    @Test
    void processesToDraftAndStoresActions() {
        Meeting m = meeting(Meeting.STATUS_UPLOADING);
        when(meetings.findById(1L)).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("m1")).thenReturn(List.of(chunk()));
        MeetingTranscript transcript = new MeetingTranscript("こんにちは",
                List.of(new MeetingTranscript.Segment(0, 10, "田中", "こんにちは")));
        when(transcription.transcribe(any(), any())).thenReturn(transcript);
        MinutesService.ActionDraft action = new MinutesService.ActionDraft("タスク", "田中", "2026-11-30", "ref");
        when(minutes.generate(any(), any())).thenReturn(new MinutesService.Result("{\"title\":\"x\"}", List.of(action)));

        processor.process(1L);

        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_DRAFT);
        assertThat(m.getMinutesJson()).isEqualTo("{\"title\":\"x\"}");
        assertThat(m.getErrorMessage()).isNull();
        verify(actions).replace(m, List.of(action));
    }

    @Test
    void failsWhenTranscriptionThrows() {
        Meeting m = meeting(Meeting.STATUS_UPLOADING);
        when(meetings.findById(1L)).thenReturn(Optional.of(m));
        when(chunks.findByMeetingIdOrderBySeqAsc("m1")).thenReturn(List.of(chunk()));
        when(transcription.transcribe(any(), any())).thenThrow(new RuntimeException("boom"));

        processor.process(1L);

        assertThat(m.getStatus()).isEqualTo(Meeting.STATUS_FAILED);
        assertThat(m.getErrorMessage()).isEqualTo("boom");
    }
}
