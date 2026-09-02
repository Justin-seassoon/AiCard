package com.aicard.gateway.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioFrameCodecTest {

    @Test
    void roundTripsAudioChunkFrame() {
        AudioChunkFrame f = new AudioChunkFrame("t1", 3, 1700000000123L, (byte) 1, (byte) 1, new byte[]{10, 20, 30});
        byte[] encoded = AudioFrameCodec.encodeAudioChunk(f);
        AudioChunkFrame back = AudioFrameCodec.decodeAudioChunk(encoded);

        assertThat(back.turnId()).isEqualTo("t1");
        assertThat(back.sequence()).isEqualTo(3);
        assertThat(back.timestamp()).isEqualTo(1700000000123L);
        assertThat(back.sourceSide()).isEqualTo((byte) 1);
        assertThat(back.inputSource()).isEqualTo((byte) 1);
        assertThat(back.audio()).containsExactly(10, 20, 30);
    }

    @Test
    void roundTripsTtsAudioFrame() {
        TtsAudioFrame f = new TtsAudioFrame("t1", new byte[]{9, 8, 7});
        byte[] encoded = AudioFrameCodec.encodeTtsAudio(f);
        TtsAudioFrame back = AudioFrameCodec.decodeTtsAudio(encoded);

        assertThat(back.turnId()).isEqualTo("t1");
        assertThat(back.audio()).containsExactly(9, 8, 7);
    }

    @Test
    void rejectsWrongFrameType() {
        byte[] wrong = {0x02, 0x00, 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> AudioFrameCodec.decodeAudioChunk(wrong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTruncatedFrame() {
        byte[] truncated = {0x01, 0x03, 't', '1'}; // 声明的 turn_id_len=3 但字节不足
        assertThatThrownBy(() -> AudioFrameCodec.decodeAudioChunk(truncated))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
