package com.aicard.gateway.protocol;

/**
 * 下行 TTS 音频帧（0x81）。字段布局见《端云通信接口规范》§2.2。
 * 流结束由 JSON {@code tts_end} 标记，帧本身不携带结束标记。
 *
 * @param turnId 轮次标识
 * @param audio  TTS 音频字节（流式分片下发）
 */
public record TtsAudioFrame(String turnId, byte[] audio) {}
