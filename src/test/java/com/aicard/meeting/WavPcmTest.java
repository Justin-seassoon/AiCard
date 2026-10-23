package com.aicard.meeting;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WavPcmTest {

    @Test
    void parses16kMono() {
        WavPcm.Audio a = WavPcm.parse(wav(16000, 1, 1, 1000));
        assertThat(a.seconds()).isEqualTo(1);
        assertThat(a.pcm16()).hasSize(32000);
    }

    @Test
    void downmixesStereoAndResamplesTo16k() {
        WavPcm.Audio a = WavPcm.parse(wav(8000, 2, 1, 1000));
        assertThat(a.seconds()).isEqualTo(1);
        assertThat(a.pcm16()).hasSize(32000); // 8k → 16k，1 秒仍是 16000 帧
    }

    @Test
    void resamples48k() {
        WavPcm.Audio a = WavPcm.parse(wav(48000, 1, 1, 1000));
        assertThat(a.pcm16()).hasSize(32000);
    }

    @Test
    void rejectsNonWav() {
        assertThatThrownBy(() -> WavPcm.parse(new byte[100]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WAV");
    }

    @Test
    void rejectsNonPcm16() {
        byte[] w = wav(16000, 1, 1, 1000);
        w[20] = 3; // audioFormat = IEEE float
        assertThatThrownBy(() -> WavPcm.parse(w))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PCM 16-bit");
    }

    /** 生成 PCM16 WAV（正弦波，便于验证下混/重采样不越界）。 */
    static byte[] wav(int rate, int channels, int seconds, int amplitude) {
        int frames = rate * seconds;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * amplitude);
            for (int c = 0; c < channels; c++) {
                data.write(v & 0xff);
                data.write((v >> 8) & 0xff);
            }
        }
        byte[] pcm = data.toByteArray();
        int byteRate = rate * channels * 2;
        byte[] out = new byte[44 + pcm.length];
        ascii(out, 0, "RIFF");
        intLe(out, 4, 36 + pcm.length);
        ascii(out, 8, "WAVE");
        ascii(out, 12, "fmt ");
        intLe(out, 16, 16);
        shortLe(out, 20, 1);
        shortLe(out, 22, channels);
        intLe(out, 24, rate);
        intLe(out, 28, byteRate);
        shortLe(out, 32, channels * 2);
        shortLe(out, 34, 16);
        ascii(out, 36, "data");
        intLe(out, 40, pcm.length);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    private static void ascii(byte[] b, int off, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, b, off, bytes.length);
    }

    private static void intLe(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static void shortLe(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }
}
