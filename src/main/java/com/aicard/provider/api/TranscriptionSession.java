package com.aicard.provider.api;

/**
 * 流式语音识别会话：音频分片到达即 push，eou 时 finish 收口取最终文本。
 * 与 {@link TurnSession} 的区别是只做 ASR（不做翻译/合成），供员工知识问答边上传边识别。
 */
public interface TranscriptionSession {

    /** 推送一段音频分片（PCM16 · 16kHz · 单声道）。 */
    void push(byte[] audioChunk);

    /** 关流并返回最终识别文本；无有效语音时抛 {@link ProviderException}。 */
    String finish();

    /** 打断/放弃本会话，释放识别器与流。 */
    void cancel();
}
