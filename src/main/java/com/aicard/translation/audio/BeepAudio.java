package com.aicard.translation.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 提示音：两短声 440Hz beep（各 100ms，间隔 100ms），PCM16 · 16kHz · 单声道 · little-endian。
 * VKB 命中后提示服务人员用。由 sendTtsChunk 按 ≤8KB 分片下发。
 */
public final class BeepAudio {

    private static final int SAMPLE_RATE = 16000;
    private static final int BEEP_MS = 100;
    private static final int GAP_MS = 100;
    private static final double FREQ = 440.0;
    private static final short AMPLITUDE = 8000;

    private BeepAudio() {}

    public static byte[] beepBeep() {
        int beepSamples = SAMPLE_RATE * BEEP_MS / 1000;
        int gapSamples = SAMPLE_RATE * GAP_MS / 1000;
        ByteBuffer buf = ByteBuffer.allocate((beepSamples * 2 + gapSamples) * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeTone(buf, beepSamples);
        writeSilence(buf, gapSamples);
        writeTone(buf, beepSamples);
        return buf.array();
    }

    private static void writeTone(ByteBuffer buf, int samples) {
        for (int i = 0; i < samples; i++) {
            short s = (short) (Math.sin(2 * Math.PI * FREQ * i / SAMPLE_RATE) * AMPLITUDE);
            buf.putShort(s);
        }
    }

    private static void writeSilence(ByteBuffer buf, int samples) {
        for (int i = 0; i < samples; i++) {
            buf.putShort((short) 0);
        }
    }
}
