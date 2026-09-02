package com.aicard.provider.azure;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.SpeechTranslationResult;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Azure 冒烟测试：仅当设置了 AZURE_SPEECH_KEY 环境变量时运行。
 * 自包含：SDK TTS 生成 PCM → 一步流式翻译（speech-to-speech）+ LID。验证真实连通与时延。
 */
@EnabledIfEnvironmentVariable(named = "AZURE_SPEECH_KEY", matches = ".+")
class AzureSpeechProviderSmokeTest {

    @Test
    void translatesAndDetects() throws Exception {
        AzureSpeechProvider provider = new AzureSpeechProvider(
                System.getenv("AZURE_SPEECH_KEY"),
                System.getenv().getOrDefault("AZURE_SPEECH_REGION", "japaneast"));

        // 用 SDK 直接生成一段中文语音 PCM（测试输入）
        byte[] pcm = synthPcm("你好，这是测试。");

        // 1) 一步流式翻译：中文 → 日文，测首帧时延
        long t0 = System.currentTimeMillis();
        AtomicLong firstChunkMs = new AtomicLong(-1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SpeechTranslationResult tr = provider.translateToSpeech(pcm, "zh-CN", "ja", chunk -> {
            firstChunkMs.compareAndSet(-1, System.currentTimeMillis() - t0);
            out.writeBytes(chunk);
        });
        long transTotalMs = System.currentTimeMillis() - t0;
        System.out.println("[时延] 一步翻译首帧 = " + firstChunkMs.get() + " ms / 完整 = " + transTotalMs
                + " ms / 原文=" + tr.finalText() + " / 译文=" + tr.translatedText()
                + " / 语音=" + out.size() + " 字节");
        assertThat(tr.translatedText()).isNotBlank();

        // 2) LID
        long t1 = System.currentTimeMillis();
        LidResult lid = provider.detectLanguage(pcm, List.of("zh-CN", "ja-JP", "en-US", "ko-KR"));
        long lidMs = System.currentTimeMillis() - t1;
        System.out.println("[时延] LID = " + lidMs + " ms / 语言=" + lid.lidLang());
        assertThat(lid.lidLang()).isNotBlank();
    }

    private static byte[] synthPcm(String text) throws Exception {
        SpeechConfig config = SpeechConfig.fromSubscription(
                System.getenv("AZURE_SPEECH_KEY"),
                System.getenv().getOrDefault("AZURE_SPEECH_REGION", "japaneast"));
        config.setSpeechSynthesisVoiceName("zh-CN-XiaoxiaoNeural");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SpeechSynthesizer syn = new SpeechSynthesizer(config, null)) {
            syn.Synthesizing.addEventListener((o, e) -> {
                byte[] a = e.getResult().getAudioData();
                if (a != null && a.length > 0) {
                    out.writeBytes(a);
                }
            });
            syn.SpeakTextAsync(text).get();
        }
        return out.toByteArray();
    }
}
