package com.aicard.provider.mock;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.api.TurnSession;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * 可编程 Mock 供应商：测试 / 开发时控制返回与失败开关。
 */
public class MockSpeechProvider implements SpeechProvider {

    private volatile LidResult lidResult = new LidResult("ja-JP", 0.9);
    private volatile String finalText = "こんにちは";
    private volatile String translatedText = "你好";
    private volatile byte[] ttsAudio = new byte[]{1, 2, 3};
    private volatile String transcribeText = "早餐时间";
    private volatile boolean failAll = false;
    private volatile boolean failNext = false;

    public void setLidResult(LidResult lidResult) { this.lidResult = lidResult; }
    public void setFinalText(String finalText) { this.finalText = finalText; }
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }
    public void setTtsAudio(byte[] ttsAudio) { this.ttsAudio = ttsAudio; }
    public void setTranscribeText(String transcribeText) { this.transcribeText = transcribeText; }
    public void setFailAll(boolean failAll) { this.failAll = failAll; }
    public void failNext() { this.failNext = true; }

    @Override
    public String name() { return "mock"; }

    @Override
    public LidResult detectLanguage(byte[] audio, List<String> candidates) {
        throwIfFail();
        return lidResult;
    }

    @Override
    public SpeechTranslationResult translateToSpeech(byte[] audio, String src, String tgt, Consumer<byte[]> onAudioChunk) {
        throwIfFail();
        onAudioChunk.accept(ttsAudio);
        return new SpeechTranslationResult(finalText, translatedText);
    }

    @Override
    public String transcribe(byte[] audio, String language) {
        throwIfFail();
        return transcribeText;
    }

    @Override
    public byte[] synthesize(String text, String language) {
        throwIfFail();
        return ttsAudio;
    }

    @Override
    public TurnSession startTurn(String src, String tgt, Consumer<byte[]> onTtsAudioChunk) {
        throwIfFail();
        return new MockTurnSession(onTtsAudioChunk, ttsAudio, finalText, translatedText, null);
    }

    @Override
    public TurnSession startTurnAuto(List<String> candidates, String tgt, Consumer<byte[]> onTtsAudioChunk) {
        throwIfFail();
        return new MockTurnSession(onTtsAudioChunk, ttsAudio, finalText, translatedText, lidResult.lidLang());
    }

    private void throwIfFail() {
        if (failAll || failNext) {
            failNext = false;
            throw new ProviderException("mock provider failure");
        }
    }

    /** 流式 turn：累积 push 的音频，finish 回调 TTS 并返回预设结果。 */
    private static final class MockTurnSession implements TurnSession {

        private final Consumer<byte[]> onTts;
        private final byte[] ttsAudio;
        private final String finalText;
        private final String translatedText;
        private final String detectedLanguage;
        private final ByteArrayOutputStream collected = new ByteArrayOutputStream();

        MockTurnSession(Consumer<byte[]> onTts, byte[] ttsAudio, String finalText, String translatedText,
                        String detectedLanguage) {
            this.onTts = onTts;
            this.ttsAudio = ttsAudio;
            this.finalText = finalText;
            this.translatedText = translatedText;
            this.detectedLanguage = detectedLanguage;
        }

        @Override
        public void push(byte[] audioChunk) {
            collected.writeBytes(audioChunk);
        }

        @Override
        public SpeechTranslationResult finish() {
            onTts.accept(ttsAudio);
            return new SpeechTranslationResult(finalText, translatedText, detectedLanguage);
        }

        @Override
        public void cancel() {
        }
    }
}
