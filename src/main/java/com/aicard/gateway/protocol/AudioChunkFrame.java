package com.aicard.gateway.protocol;

/**
 * 上行音频分片帧（0x01）。字段布局见《端云通信接口规范》§2.1。
 *
 * @param turnId      轮次标识（设备生成 UUID）
 * @param sequence    turn 内分片序号，从 0 递增，不跨 turn 复用
 * @param timestamp   会话内相对毫秒（自建连起）
 * @param sourceSide  说话方：0=wearer 1=counterparty 2=uncertain
 * @param inputSource 输入通道：0=badge_near 1=badge_far 2=headset_mic 3=uncertain
 * @param audio       原始音频字节
 */
public record AudioChunkFrame(String turnId, int sequence, long timestamp,
                              byte sourceSide, byte inputSource, byte[] audio) {}
