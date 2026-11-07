package com.aicard.provider.api;

import java.io.ByteArrayOutputStream;
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

    /** 语音转文本（ASR）。员工知识库问答用 staff_language 转写。 */
    String transcribe(byte[] audio, String language);

    /**
     * 开启流式语音识别（ASR）会话：音频分片边到达边识别，eou 时 finish 取最终文本。
     * 默认实现仅在 finish 时对整个音频调用一次 {@link #transcribe}（不支持流式的供应商可退化为整段识别）。
     */
    default TranscriptionSession startTranscription(String language) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        return new TranscriptionSession() {
            @Override
            public void push(byte[] audioChunk) {
                buffer.writeBytes(audioChunk);
            }

            @Override
            public String finish() {
                return transcribe(buffer.toByteArray(), language);
            }

            @Override
            public void cancel() {
                buffer.reset();
            }
        };
    }

    /** 文本转语音（TTS），返回 PCM 音频字节。 */
    byte[] synthesize(String text, String language);

    /**
     * 流式文本转语音：边合成边回调音频分片（首分片含 44 字节 WAV 头，后续为裸 PCM16 · 16kHz · 单声道）。
     * 默认退化为一次性 synthesize 后整体回调，支持流式的供应商应 override。
     */
    default void synthesizeStream(String text, String language, Consumer<byte[]> onAudioChunk) {
        onAudioChunk.accept(synthesize(text, language));
    }

    /** 开启流式翻译 turn（fixed 模式）：边 push 音频边识别/翻译/流式合成语音。 */
    TurnSession startTurn(String src, String tgt, Consumer<byte[]> onTtsAudioChunk);

    /** 开启一步 auto-detect 流式翻译 turn（auto 模式）：自动检测源语言并翻译到 tgt。 */
    TurnSession startTurnAuto(List<String> candidates, String tgt, Consumer<byte[]> onTtsAudioChunk);
}
