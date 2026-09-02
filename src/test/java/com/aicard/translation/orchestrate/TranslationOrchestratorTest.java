package com.aicard.translation.orchestrate;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.mock.MockSpeechProvider;
import com.aicard.translation.decision.LidDecisionEngine;
import com.aicard.translation.state.TranslationSessionState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationOrchestratorTest {

    private final MockSpeechProvider provider = new MockSpeechProvider();
    private final TranslationOrchestrator orchestrator =
            new TranslationOrchestrator(provider, new LidDecisionEngine());

    @Test
    void translatesCounterpartyUtteranceEndToEnd() {
        provider.setLidResult(new LidResult("zh-CN", 0.9));
        provider.setTranslatedText("こんにちは");
        provider.setTtsAudio(new byte[]{7, 8});
        List<byte[]> chunks = new ArrayList<>();

        TranslationResult r = orchestrator.translateUtterance(
                new byte[]{1}, "counterparty", "auto", null, false, new TranslationSessionState(),
                List.of("zh-CN", "en-US", "ko-KR"), "ja-JP", chunks::add);

        assertThat(r.action()).isEqualTo("translate");
        assertThat(chunks).containsExactly(new byte[]{7, 8});
        assertThat(r.newVisitorLanguage()).isEqualTo("zh-CN");
    }

    @Test
    void fixedModeSkipsLidAndTranslates() {
        // fixed 模式：跳过 LID，直接按 lang_pair 双向翻译
        provider.setTranslatedText("こんにちは");
        provider.setTtsAudio(new byte[]{9});
        List<byte[]> chunks = new ArrayList<>();

        TranslationResult r = orchestrator.translateUtterance(
                new byte[]{1}, "counterparty", "fixed", "zh-ja", false, new TranslationSessionState(),
                List.of(), "ja-JP", chunks::add);

        assertThat(r.action()).isEqualTo("translate");
        assertThat(chunks).containsExactly(new byte[]{9});
    }

    @Test
    void fixedModeWearerTranslatesBackward() {
        provider.setTranslatedText("你好");
        provider.setTtsAudio(new byte[]{5});
        List<byte[]> chunks = new ArrayList<>();

        TranslationResult r = orchestrator.translateUtterance(
                new byte[]{1}, "wearer", "fixed", "zh-ja", false, new TranslationSessionState(),
                List.of(), "ja-JP", chunks::add);

        assertThat(r.action()).isEqualTo("translate");
        assertThat(chunks).containsExactly(new byte[]{5});
    }

    @Test
    void lowConfidenceReturnsRepeatWithoutTts() {
        provider.setLidResult(new LidResult("zh-CN", 0.3));
        List<byte[]> chunks = new ArrayList<>();

        TranslationResult r = orchestrator.translateUtterance(
                new byte[]{1}, "counterparty", "auto", null, false, new TranslationSessionState(),
                List.of("zh-CN"), "ja-JP", chunks::add);

        assertThat(r.action()).isEqualTo("repeat");
        assertThat(r.errorCode()).isEqualTo("E_LID_LOW");
        assertThat(chunks).isEmpty();
    }

    @Test
    void whitelistHitInLockedStatePassthrough() {
        provider.setLidResult(new LidResult("en-US", 0.3));
        provider.setTranslatedText("OK");
        provider.setTtsAudio(new byte[]{9});
        TranslationSessionState state = new TranslationSessionState();
        state.lock("en-US");
        List<byte[]> chunks = new ArrayList<>();

        TranslationResult r = orchestrator.translateUtterance(
                new byte[]{1}, "counterparty", "auto", null, true, state, List.of("en-US"), "ja-JP", chunks::add);

        assertThat(r.action()).isEqualTo("translate");
        assertThat(chunks).containsExactly(new byte[]{9});
    }
}
