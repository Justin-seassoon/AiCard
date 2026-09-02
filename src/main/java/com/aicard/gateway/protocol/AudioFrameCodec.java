package com.aicard.gateway.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 二进制音频帧编解码器。多字节整数一律大端 big-endian。
 * 帧结构严格遵循《端云通信接口规范》§2：
 *   上行音频帧 0x01：frame_type(1) + turn_id_len(1) + turn_id(N) + sequence(4) + timestamp(8)
 *                    + source_side(1) + input_source(1) + audio_payload(...)
 *   下行 TTS 帧 0x81：frame_type(1) + turn_id_len(1) + turn_id(N) + tts_audio(...)
 */
public final class AudioFrameCodec {

    public static final byte AUDIO_CHUNK = 0x01;
    public static final byte TTS_AUDIO = (byte) 0x81;

    /** 上行音频帧除 audio_payload 外的定长帧头最小长度。 */
    private static final int AUDIO_CHUNK_HEADER = 1 + 1 + 4 + 8 + 1 + 1;
    /** 下行 TTS 帧除 tts_audio 外的最小长度。 */
    private static final int TTS_AUDIO_HEADER = 1 + 1;

    private AudioFrameCodec() {}

    public static byte[] encodeAudioChunk(AudioChunkFrame f) {
        byte[] turnId = f.turnId().getBytes(StandardCharsets.UTF_8);
        if (turnId.length == 0 || turnId.length > 255) {
            throw new IllegalArgumentException("turn_id must be 1..255 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(AUDIO_CHUNK_HEADER + turnId.length + f.audio().length)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(AUDIO_CHUNK);
        buf.put((byte) turnId.length);
        buf.put(turnId);
        buf.putInt(f.sequence());
        buf.putLong(f.timestamp());
        buf.put(f.sourceSide());
        buf.put(f.inputSource());
        buf.put(f.audio());
        return buf.array();
    }

    public static AudioChunkFrame decodeAudioChunk(byte[] frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        if (frame.length < AUDIO_CHUNK_HEADER || buf.get() != AUDIO_CHUNK) {
            throw new IllegalArgumentException("not an audio chunk frame");
        }
        int turnIdLen = buf.get() & 0xFF;
        if (buf.remaining() < turnIdLen + 4 + 8 + 1 + 1) {
            throw new IllegalArgumentException("truncated audio chunk frame");
        }
        byte[] turnId = new byte[turnIdLen];
        buf.get(turnId);
        int sequence = buf.getInt();
        long timestamp = buf.getLong();
        byte sourceSide = buf.get();
        byte inputSource = buf.get();
        byte[] audio = new byte[buf.remaining()];
        buf.get(audio);
        return new AudioChunkFrame(new String(turnId, StandardCharsets.UTF_8),
                sequence, timestamp, sourceSide, inputSource, audio);
    }

    public static byte[] encodeTtsAudio(TtsAudioFrame f) {
        byte[] turnId = f.turnId().getBytes(StandardCharsets.UTF_8);
        if (turnId.length == 0 || turnId.length > 255) {
            throw new IllegalArgumentException("turn_id must be 1..255 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(TTS_AUDIO_HEADER + turnId.length + f.audio().length)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(TTS_AUDIO);
        buf.put((byte) turnId.length);
        buf.put(turnId);
        buf.put(f.audio());
        return buf.array();
    }

    public static TtsAudioFrame decodeTtsAudio(byte[] frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        if (frame.length < TTS_AUDIO_HEADER || buf.get() != TTS_AUDIO) {
            throw new IllegalArgumentException("not a tts audio frame");
        }
        int turnIdLen = buf.get() & 0xFF;
        if (buf.remaining() < turnIdLen) {
            throw new IllegalArgumentException("truncated tts audio frame");
        }
        byte[] turnId = new byte[turnIdLen];
        buf.get(turnId);
        byte[] audio = new byte[buf.remaining()];
        buf.get(audio);
        return new TtsAudioFrame(new String(turnId, StandardCharsets.UTF_8), audio);
    }
}
