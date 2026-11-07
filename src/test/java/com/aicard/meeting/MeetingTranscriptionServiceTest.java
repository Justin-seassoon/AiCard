package com.aicard.meeting;

import com.aicard.provider.api.SpeechProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingTranscriptionServiceTest {

    private final SpeechProvider speech = mock(SpeechProvider.class);
    private final MeetingConfig config = mock(MeetingConfig.class);
    private final MeetingAudioStore audioStore = mock(MeetingAudioStore.class);
    private final MeetingTranscriptionService service =
            new MeetingTranscriptionService(speech, config, audioStore);

    private static Meeting meeting(int chunkSec) {
        return Meeting.builder().meetingId("m1").chunkSec(chunkSec).build();
    }

    private static MeetingChunk chunk(int seq) {
        return MeetingChunk.builder().meetingId("m1").seq(seq).storageKey("k" + seq).build();
    }

    @Test
    void emptyChunksReturnsEmptyTranscript() {
        assertThat(service.transcribe(meeting(15), List.of()).text()).isEmpty();
    }

    @Test
    void nullChunksReturnsEmptyTranscript() {
        assertThat(service.transcribe(meeting(15), null).text()).isEmpty();
    }

    @Test
    void mockProviderReturnsDemoTranscript() {
        when(speech.name()).thenReturn("mock");

        MeetingTranscript result = service.transcribe(meeting(15), List.of(chunk(0)));

        assertThat(result.text()).isNotBlank();
    }

    @Test
    void groupsChunksAndBuildsTimestampedText() {
        when(speech.name()).thenReturn("azure");
        when(config.getLanguage()).thenReturn("ja-JP");
        when(audioStore.read("k0")).thenReturn(new byte[]{1});
        when(speech.transcribe(any(), anyString())).thenReturn("こんにちは");

        MeetingTranscript result = service.transcribe(meeting(15), List.of(chunk(0)));

        assertThat(result.text()).isEqualTo("[00:00] こんにちは");
        assertThat(result.segments()).hasSize(1);
    }

    @Test
    void partialFailureStillSucceeds() {
        when(speech.name()).thenReturn("azure");
        when(config.getLanguage()).thenReturn("ja-JP");
        when(audioStore.read(anyString())).thenReturn(new byte[]{1});
        // chunkSec=30 使每片一组；第一组失败、第二组成功
        when(speech.transcribe(any(), anyString()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn("成功した区間");

        MeetingTranscript result = service.transcribe(meeting(30), List.of(chunk(0), chunk(1)));

        assertThat(result.text()).contains("成功した区間");
        assertThat(result.segments()).hasSize(1);
    }

    @Test
    void allFailuresThrow() {
        when(speech.name()).thenReturn("azure");
        when(config.getLanguage()).thenReturn("ja-JP");
        when(audioStore.read(anyString())).thenReturn(new byte[]{1});
        when(speech.transcribe(any(), anyString())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.transcribe(meeting(30), List.of(chunk(0))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refFormatsSeconds() {
        assertThat(MeetingTranscriptionService.ref(0)).isEqualTo("00:00");
        assertThat(MeetingTranscriptionService.ref(65)).isEqualTo("01:05");
        assertThat(MeetingTranscriptionService.ref(3661)).isEqualTo("01:01:01");
    }
}
