package com.aicard.provider.mock;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;

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
    private volatile boolean failAll = false;
    private volatile boolean failNext = false;

    public void setLidResult(LidResult lidResult) { this.lidResult = lidResult; }
    public void setFinalText(String finalText) { this.finalText = finalText; }
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }
    public void setTtsAudio(byte[] ttsAudio) { this.ttsAudio = ttsAudio; }
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

    private void throwIfFail() {
        if (failAll || failNext) {
            failNext = false;
            throw new ProviderException("mock provider failure");
        }
    }
}
