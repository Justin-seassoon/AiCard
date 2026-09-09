package com.aicard.translation.config;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.azure.AzureSpeechProvider;
import com.aicard.provider.mock.MockSpeechProvider;
import com.aicard.translation.decision.LidDecisionEngine;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import com.aicard.translation.qualify.UtteranceQualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Bean
    public UtteranceQualifier utteranceQualifier(
            @Value("${translation.qualify.min-utt-duration-ms:200}") long minDurationMs,
            @Value("${translation.qualify.numeric-filter:true}") boolean numericFilter,
            @Value("${translation.qualify.whitelist:}") String whitelistCsv,
            @Value("${translation.qualify.brand-terms:}") String brandTermsCsv) {
        return new UtteranceQualifier(minDurationMs, numericFilter,
                splitLowerCsv(whitelistCsv), splitLowerCsv(brandTermsCsv));
    }

    private static Set<String> splitLowerCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
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
