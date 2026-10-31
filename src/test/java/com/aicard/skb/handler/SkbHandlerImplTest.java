package com.aicard.skb.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.ingestion.model.QaLog;
import com.aicard.ingestion.service.IngestionService;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.service.SkbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkbHandlerImplTest {

    private SpeechProvider speech;
    private SkbService skb;
    private IngestionService ingestion;
    private LLMProvider llm;
    private SkbHandlerImpl handler;
    private List<OutboundMessage> texts;
    private List<TtsAudioFrame> audio;

    @BeforeEach
    void setUp() {
        speech = mock(SpeechProvider.class);
        skb = mock(SkbService.class);
        ingestion = mock(IngestionService.class);
        llm = mock(LLMProvider.class);
        handler = new SkbHandlerImpl(speech, skb, ingestion, llm);
        texts = new ArrayList<>();
        audio = new ArrayList<>();
        when(speech.synthesize(anyString(), anyString())).thenReturn(new byte[]{1, 2, 3});
    }

    @Test
    void japaneseQuestionUsesOriginalPipelineWithoutTranslation() {
        when(speech.transcribe(any(), eq("ja-JP"))).thenReturn("朝食は何時ですか");
        when(skb.answer("朝食は何時ですか", 7L, 8L, "skb"))
                .thenReturn(SkbResult.ok("朝食は7時からです", List.of("FAQ#1")));

        ask(ctx("ja-JP"));

        verify(llm, never()).translate(anyString(), anyString());
        verify(speech).synthesize("朝食は7時からです", "ja-JP");
        assertThat(answerText()).isEqualTo("朝食は7時からです");
    }

    @Test
    void chineseQuestionIsTranslatedAroundTheJapaneseKnowledgeBase() {
        when(speech.transcribe(any(), eq("zh-CN"))).thenReturn("早餐几点开始？");
        when(llm.translate("早餐几点开始？", "ja-JP")).thenReturn("朝食は何時ですか");
        when(skb.answer("朝食は何時ですか", 7L, 8L, "skb"))
                .thenReturn(SkbResult.ok("朝食は7時からです", List.of("FAQ#1")));
        when(llm.translate("朝食は7時からです", "zh-CN")).thenReturn("早餐7点开始。");

        ask(ctx("zh-CN"));

        verify(speech).transcribe(any(), eq("zh-CN"));
        verify(llm).translate("早餐几点开始？", "ja-JP");
        verify(skb).answer("朝食は何時ですか", 7L, 8L, "skb");
        verify(llm).translate("朝食は7時からです", "zh-CN");
        verify(speech).synthesize("早餐7点开始。", "zh-CN");
        assertThat(answerText()).isEqualTo("早餐7点开始。");

        verify(ingestion).recordQa(argThat((QaLog l) ->
                "早餐几点开始？".equals(l.question()) && "早餐7点开始。".equals(l.answer())));
    }

    @Test
    void questionTranslationFailureFallsBackToOriginalQuestion() {
        when(speech.transcribe(any(), eq("en-US"))).thenReturn("When is breakfast?");
        when(llm.translate("When is breakfast?", "ja-JP")).thenThrow(new ProviderException("llm down"));
        when(skb.answer("When is breakfast?", 7L, 8L, "skb"))
                .thenReturn(SkbResult.ok("Breakfast is from 7am", List.of()));
        when(llm.translate("Breakfast is from 7am", "en-US")).thenReturn("Breakfast is from 7am.");

        ask(ctx("en-US"));

        verify(skb).answer("When is breakfast?", 7L, 8L, "skb");
        verify(speech).synthesize("Breakfast is from 7am.", "en-US");
    }

    @Test
    void answerTranslationFailureReturnsProviderError() {
        when(speech.transcribe(any(), eq("vi-VN"))).thenReturn("Bữa sáng mấy giờ?");
        when(llm.translate("Bữa sáng mấy giờ?", "ja-JP")).thenReturn("朝食は何時ですか");
        when(skb.answer("朝食は何時ですか", 7L, 8L, "skb"))
                .thenReturn(SkbResult.ok("朝食は7時からです", List.of()));
        when(llm.translate("朝食は7時からです", "vi-VN")).thenThrow(new ProviderException("llm down"));

        ask(ctx("vi-VN"));

        assertThat(texts).anyMatch(m -> "error".equals(m.type()) && "E_PROVIDER".equals(m.code()));
        verify(speech, never()).synthesize(anyString(), anyString());
    }

    private SessionContext ctx(String staffLanguage) {
        return new SessionContext("s1", "dev-1", 7L, 8L, "staff_qa", "fixed", staffLanguage,
                texts::add, audio::add);
    }

    private void ask(SessionContext ctx) {
        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 0, (byte) 0, (byte) 0, new byte[]{1}));
        handler.onEndOfUtterance(ctx, InboundMessage.builder().type("eou").turnId("t1").build());
    }

    private String answerText() {
        return texts.stream().filter(m -> "answer".equals(m.type())).findFirst()
                .map(OutboundMessage::text).orElse(null);
    }
}
