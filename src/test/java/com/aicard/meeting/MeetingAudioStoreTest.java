package com.aicard.meeting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingAudioStoreTest {

    @TempDir
    Path tmp;

    @Test
    void writesReadsAndConcatenatesPcmWithWavHeader() {
        MeetingConfig config = mock(MeetingConfig.class);
        when(config.getStorageDir()).thenReturn(tmp.toString());
        MeetingAudioStore store = new MeetingAudioStore(config);

        String key0 = store.write(7L, "mtg-1", 0, "pcm16", new byte[]{1, 2, 3, 4});
        String key1 = store.write(7L, "mtg-1", 1, "pcm16", new byte[]{5, 6});

        assertThat(key0).isEqualTo("7/mtg-1/00000000.pcm");
        assertThat(store.read(key0)).containsExactly(1, 2, 3, 4);

        Meeting meeting = Meeting.builder().meetingId("mtg-1").codec("pcm16").build();
        List<MeetingChunk> chunks = List.of(
                MeetingChunk.builder().seq(0).storageKey(key0).build(),
                MeetingChunk.builder().seq(1).storageKey(key1).build());
        byte[] wav = store.concat(meeting, chunks, 16000);

        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(wav.length).isEqualTo(44 + 6);
        assertThat(wav[44]).isEqualTo((byte) 1);

        store.deleteMeeting(7L, "mtg-1");
        assertThat(tmp.resolve("7/mtg-1")).doesNotExist();
    }

    @Test
    void rejectsPathTraversal() {
        MeetingConfig config = mock(MeetingConfig.class);
        when(config.getStorageDir()).thenReturn(tmp.toString());
        MeetingAudioStore store = new MeetingAudioStore(config);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> store.write(7L, "../etc", 0, "pcm16", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
