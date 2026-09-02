package com.aicard.translation.config;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.azure.AzureSpeechProvider;
import com.aicard.provider.mock.MockSpeechProvider;
import com.aicard.translation.decision.LidDecisionEngine;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 翻译链路装配：设置环境变量 AZURE_SPEECH_KEY 时用真实 Azure，否则用 Mock（测试/开发）。
 */
@Configuration
public class TranslationConfig {

    @Value("${azure.speech.key:}")
    private String azureKey;

    @Value("${azure.speech.region:japaneast}")
    private String azureRegion;

    @Bean
    public LidDecisionEngine lidDecisionEngine() {
        return new LidDecisionEngine();
    }

    @Bean
    public SpeechProvider speechProvider() {
        if (azureKey != null && !azureKey.isBlank()) {
            return new AzureSpeechProvider(azureKey, azureRegion);
        }
        return mockProvider();
    }

    private MockSpeechProvider mockProvider() {
        MockSpeechProvider mock = new MockSpeechProvider();
        mock.setLidResult(new LidResult("zh-CN", 0.9));
        mock.setFinalText("你好");
        mock.setTranslatedText("こんにちは");
        mock.setTtsAudio(beep());
        return mock;
    }

    @Bean
    public TranslationOrchestrator translationOrchestrator(SpeechProvider provider, LidDecisionEngine decision) {
        return new TranslationOrchestrator(provider, decision);
    }

    private static byte[] beep() {
        int sampleRate = 16000;
        int samples = sampleRate * 200 / 1000;
        ByteBuffer buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            buf.putShort((short) (Math.sin(2 * Math.PI * 440.0 * i / sampleRate) * 8000));
        }
        return buf.array();
    }
}
