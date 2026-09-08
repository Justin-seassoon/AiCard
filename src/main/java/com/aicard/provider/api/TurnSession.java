package com.aicard.provider.api;

/**
 * 一个流式翻译 turn：边 push 音频边识别/翻译/合成（fixed 模式「边说边识别」用）。
 * push 流式喂音频；finish 关输入流并等待最终翻译结果；cancel 用于打断清理（stop_tts）。
 */
public interface TurnSession {
    void push(byte[] audioChunk);
    SpeechTranslationResult finish();
    void cancel();
}
