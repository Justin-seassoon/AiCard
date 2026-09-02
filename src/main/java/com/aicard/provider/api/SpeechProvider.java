package com.aicard.provider.api;

import java.util.List;
import java.util.function.Consumer;

/**
 * 供应商组合接口。LID 独立前置（软锁定用）；翻译为「语音→译文语音」一步流式
 * （speech-to-speech，边翻译边流式回调合成语音，Live Interpreter 式）。新增供应商 = 新增实现类。
 */
public interface SpeechProvider {
    String name();

    /** 语种判定（独立前置，AUTO 模式软锁定决策用；fixed 模式跳过）。 */
    LidResult detectLanguage(byte[] audio, List<String> candidates);

    /** 一步流式语音翻译 + TTS：语音 → 译文语音分片（onAudioChunk 回调），返回译文文本。 */
    SpeechTranslationResult translateToSpeech(byte[] audio, String src, String tgt, Consumer<byte[]> onAudioChunk);
}
