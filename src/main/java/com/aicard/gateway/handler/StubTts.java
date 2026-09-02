package com.aicard.gateway.handler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 联调桩的固定 TTS 音频：一段 400ms 440Hz 正弦波（PCM16 LE · 16kHz · 单声道）。
 * 采样率/编码属接口规范 §9 待冻结项，此处仅为让 ODM 验证 TTS 帧能收到并可播放。
 */
final class StubTts {

    private StubTts() {}

    static byte[] beep() {
        int sampleRate = 16000;
        int durationMs = 200; // 200ms × 16kHz × 2 字节 = 6400 字节，符合单帧 ≤8KB（接口规范 §9）
        int samples = sampleRate * durationMs / 1000;
        ByteBuffer buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            short s = (short) (Math.sin(2 * Math.PI * 440.0 * i / sampleRate) * 8000);
            buf.putShort(s);
        }
        return buf.array();
    }
}
