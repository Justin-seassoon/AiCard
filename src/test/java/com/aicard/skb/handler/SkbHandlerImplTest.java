package com.aicard.skb.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.ingestion.service.IngestionService;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.TranscriptionSession;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.service.SkbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

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
        doAnswer(inv -> {
            inv.<Consumer<byte[]>>getArgument(2).accept(new byte[]{1, 2, 3});
            return null;
        }).when(speech).synthesizeStream(anyString(), anyString(), any());
    }

    @Test
    void japaneseQuestionUsesOriginalPipelineWithoutTranslation() {
        when(speech.transcribe(any(), eq("ja-JP"))).thenReturn("朝食は何時ですか");
        when(skb.answer("朝食は何時ですか", 7L, 8L, "skb"))
                .thenReturn(SkbResult.ok("朝食は7時からです", List.of("FAQ#1")));

        ask(ctx("ja-JP"));

        verify(llm, never()).translate(anyString(), anyString());
        verify(speech).synthesizeStream(eq("朝食は7時からです"), eq("ja-JP"), any());
        assertThat(answerText()).isEqualTo("朝食は7時からです");
    }

    @Test
    void chineseQuestionUsesCrossLingualRetrievalAndLocalizedAnswer() {
        when(speech.transcribe(any(), eq("zh-CN"))).thenReturn("早餐几点开始？");
        when(skb.answerLocalized("早餐几点开始？", 7L, 8L, "skb", "zh-CN"))
                .thenReturn(new SkbService.LocalizedAnswer(
                        SkbResult.ok("朝食は7時からです", List.of("FAQ#1")), "早餐7点开始。"));

        ask(ctx("zh-CN"));

        verify(speech).transcribe(any(), eq("zh-CN"));
        verify(llm, never()).translate(anyString(), anyString());
        verify(skb).answerLocalized("早餐几点开始？", 7L, 8L, "skb", "zh-CN");
        verify(speech).synthesizeStream(eq("早餐7点开始。"), eq("zh-CN"), any());
        assertThat(answerText()).isEqualTo("早餐7点开始。");
    }

    @Test
    void chineseNoMatchFallsBackToJapaneseQuestion() {
        when(speech.transcribe(any(), eq("zh-CN"))).thenReturn("早餐几点开始？");
        when(skb.answerLocalized("早餐几点开始？", 7L, 8L, "skb", "zh-CN"))
                .thenReturn(new SkbService.LocalizedAnswer(SkbResult.noMatch(), null));
        when(llm.translate("早餐几点开始？", "ja-JP")).thenReturn("朝食は何時ですか");
        when(skb.answerLocalized("朝食は何時ですか", 7L, 8L, "skb", "zh-CN"))
                .thenReturn(new SkbService.LocalizedAnswer(
                        SkbResult.ok("朝食は7時からです", List.of("FAQ#1")), "早餐7点开始。"));

        ask(ctx("zh-CN"));

        verify(llm).translate("早餐几点开始？", "ja-JP");
        verify(skb).answerLocalized("朝食は何時ですか", 7L, 8L, "skb", "zh-CN");
        verify(speech).synthesizeStream(eq("早餐7点开始。"), eq("zh-CN"), any());
        assertThat(answerText()).isEqualTo("早餐7点开始。");
    }

    @Test
    void questionTranslationFailureKeepsNoMatchAnswer() {
        when(speech.transcribe(any(), eq("en-US"))).thenReturn("When is breakfast?");
        when(skb.answerLocalized("When is breakfast?", 7L, 8L, "skb", "en-US"))
                .thenReturn(new SkbService.LocalizedAnswer(SkbResult.noMatch(), null));
        when(llm.translate("When is breakfast?", "ja-JP")).thenThrow(new ProviderException("llm down"));
        when(llm.translate("知識ベースに明確な記載がありません", "en-US"))
                .thenReturn("There is no clear answer in the knowledge base.");

        ask(ctx("en-US"));

        verify(speech).synthesizeStream(eq("There is no clear answer in the knowledge base."), eq("en-US"), any());
    }

    @Test
    void answerLocalizationFailureReturnsProviderError() {
        when(speech.transcribe(any(), eq("vi-VN"))).thenReturn("Bữa sáng mấy giờ?");
        when(skb.answerLocalized("Bữa sáng mấy giờ?", 7L, 8L, "skb", "vi-VN"))
                .thenReturn(new SkbService.LocalizedAnswer(
                        SkbResult.ok("朝食は7時からです", List.of()), null));
        when(llm.translate("朝食は7時からです", "vi-VN")).thenThrow(new ProviderException("llm down"));

        ask(ctx("vi-VN"));

        assertThat(texts).anyMatch(m -> "error".equals(m.type()) && "E_PROVIDER".equals(m.code()));
        verify(speech, never()).synthesizeStream(anyString(), anyString(), any());
    }

    @Test
    void streamsTtsInAtMost8KbFramesThenTtsEnd() {
        when(speech.transcribe(any(), eq("ja-JP"))).thenReturn("朝食は何時ですか");
        when(skb.answer("朝食は何時ですか", 7L, 8L, "skb"))
                .thenReturn(SkbResult.ok("朝食は7時からです", List.of("FAQ#1")));
        byte[] tts = new byte[9000];
        for (int i = 0; i < tts.length; i++) {
            tts[i] = (byte) i;
        }
        doAnswer(inv -> {
            Consumer<byte[]> onChunk = inv.getArgument(2);
            onChunk.accept(Arrays.copyOfRange(tts, 0, 3000));
            onChunk.accept(Arrays.copyOfRange(tts, 3000, 9000));
            return null;
        }).when(speech).synthesizeStream(anyString(), anyString(), any());

        ask(ctx("ja-JP"));

        assertThat(audio).hasSize(2);
        assertThat(audio.get(0).turnId()).isEqualTo("t1");
        assertThat(audio.get(0).audio()).hasSize(8000);
        assertThat(audio.get(1).audio()).hasSize(1000);
        byte[] joined = new byte[tts.length];
        System.arraycopy(audio.get(0).audio(), 0, joined, 0, 8000);
        System.arraycopy(audio.get(1).audio(), 0, joined, 8000, 1000);
        assertThat(joined).isEqualTo(tts);
        assertThat(texts).anyMatch(m -> "tts_end".equals(m.type()));
    }

    @Test
    void ttsStreamFailureReturnsProviderErrorWithoutTtsEnd() {
        when(speech.transcribe(any(), eq("ja-JP"))).thenReturn("朝食は何時ですか");
        when(skb.answer("朝食は何時ですか", 7L, 8L, "skb"))
                .thenReturn(SkbResult.ok("朝食は7時からです", List.of("FAQ#1")));
        doThrow(new ProviderException("tts down"))
                .when(speech).synthesizeStream(anyString(), anyString(), any());

        ask(ctx("ja-JP"));

        assertThat(texts).anyMatch(m -> "error".equals(m.type()) && "E_PROVIDER".equals(m.code()));
        assertThat(texts).noneMatch(m -> "tts_end".equals(m.type()));
        assertThat(audio).isEmpty();
    }

    @Test
    void streamsAsrWhenProviderSupportsIt() {
        TranscriptionSession asr = mock(TranscriptionSession.class);
        when(speech.startTranscription("ja-JP")).thenReturn(asr);
        when(asr.finish()).thenReturn("朝食は何時ですか");
        when(skb.answer("朝食は何時ですか", 7L, 8L, "skb"))
                .thenReturn(SkbResult.ok("朝食は7時からです", List.of("FAQ#1")));

        ask(ctx("ja-JP"));

        verify(asr).push(any());
        verify(asr).finish();
        verify(speech, never()).transcribe(any(), anyString());
        assertThat(answerText()).isEqualTo("朝食は7時からです");
    }

    @Test
    void asrFailureReturnsAsrError() {
        TranscriptionSession asr = mock(TranscriptionSession.class);
        when(speech.startTranscription("ja-JP")).thenReturn(asr);
        when(asr.finish()).thenThrow(new ProviderException("asr down"));

        ask(ctx("ja-JP"));

        assertThat(texts).anyMatch(m -> "error".equals(m.type()) && "E_ASR_FAIL".equals(m.code()));
        verify(speech, never()).synthesizeStream(anyString(), anyString(), any());
        assertThat(texts).noneMatch(m -> "tts_end".equals(m.type()));
    }

    @Test
    void japaneseNoMatchPlaysFallbackAnswer() {
        when(speech.transcribe(any(), eq("ja-JP"))).thenReturn("朝食は何時ですか");
        when(skb.answer("朝食は何時ですか", 7L, 8L, "skb")).thenReturn(SkbResult.noMatch());

        ask(ctx("ja-JP"));

        verify(llm, never()).translate(anyString(), anyString());
        assertThat(answerText()).isEqualTo("知識ベースに明確な記載がありません");
        verify(speech).synthesizeStream(eq("知識ベースに明確な記載がありません"), eq("ja-JP"), any());
        assertThat(texts).anyMatch(m -> "tts_end".equals(m.type()));
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
