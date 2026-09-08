package com.aicard.translation.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.ingestion.service.IngestionService;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.api.TurnSession;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import com.aicard.translation.orchestrate.TranslationResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslationHandlerImplTest {

    private final IngestionService ingestion = mock(IngestionService.class);

    private static InboundMessage eou(String turnId) {
        return eou(turnId, "counterparty");
    }

    private static InboundMessage eou(String turnId, String sourceSide) {
        return InboundMessage.builder().type("eou").sessionId("s1").turnId(turnId)
                .sourceSide(sourceSide).build();
    }

    private static SessionContext ctx(CopyOnWriteArrayList<OutboundMessage> text,
                                      CopyOnWriteArrayList<TtsAudioFrame> binary, String translationMode) {
        return new SessionContext("s1", "dev-1", 1L, 2L, "translate", translationMode, "ja-JP",
                text::add, binary::add);
    }

    @Test
    void autoWearerAccumulatesAudioAndSendsTtsOnEou() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        when(orchestrator.translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<byte[]> onTts = invocation.getArgument(8);
                    onTts.accept(new byte[]{5, 6});
                    return new TranslationResult("translate", null, "zh-CN");
                });

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator, speech, ingestion);
        SessionContext ctx = ctx(textSent, binarySent, "auto");

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 0, (byte) 1, new byte[]{1, 2}));
        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 1, 2L, (byte) 0, (byte) 1, new byte[]{3, 4}));
        handler.onEndOfUtterance(ctx, eou("t1", "wearer"));

        assertThat(binarySent).anyMatch(f -> f.turnId().equals("t1") && f.audio().length == 2);
        assertThat(textSent).anyMatch(m -> "tts_end".equals(m.type()));
        assertThat(textSent).anyMatch(m -> "language_state".equals(m.type())
                && "zh-CN".equals(m.visitorLanguage()));
    }

    @Test
    void autoWearerLowConfidenceSendsError() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        when(orchestrator.translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new TranslationResult("repeat", "E_LID_LOW", "UNKNOWN"));

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator, speech, ingestion);
        SessionContext ctx = ctx(textSent, binarySent, "auto");

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 0, (byte) 1, new byte[]{1, 2}));
        handler.onEndOfUtterance(ctx, eou("t1", "wearer"));

        assertThat(textSent).anyMatch(m -> "error".equals(m.type()) && "E_LID_LOW".equals(m.code()));
    }

    @Test
    void fixedModeStreamsAudioAndFinishesOnEou() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TurnSession turn = mock(TurnSession.class);
        when(speech.startTurn(eq("zh"), eq("ja"), any())).thenReturn(turn);
        when(turn.finish()).thenReturn(new SpeechTranslationResult("你好", "こんにちは"));

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator, speech, ingestion);
        SessionContext ctx = ctx(textSent, binarySent, "fixed");
        ctx.langPair("zh-ja");

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 1, (byte) 1, new byte[]{1, 2}));
        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 1, 2L, (byte) 1, (byte) 1, new byte[]{3, 4}));
        handler.onEndOfUtterance(ctx, eou("t1"));

        verify(speech).startTurn(eq("zh"), eq("ja"), any());
        verify(turn, times(2)).push(any(byte[].class));
        verify(turn).finish();
        assertThat(textSent).anyMatch(m -> "tts_end".equals(m.type()));
    }

    @Test
    void fixedModeCancelsOnStopTts() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TurnSession turn = mock(TurnSession.class);
        when(speech.startTurn(eq("zh"), eq("ja"), any())).thenReturn(turn);

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator, speech, ingestion);
        SessionContext ctx = ctx(textSent, binarySent, "fixed");
        ctx.langPair("zh-ja");

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 1, (byte) 1, new byte[]{1}));
        handler.onStopTts(ctx, InboundMessage.builder().type("stop_tts").sessionId("s1").turnId("t1").build());

        verify(turn).cancel();
    }

    @Test
    void fixedModeUncertainFallsBackToAccumulate() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        when(orchestrator.translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new TranslationResult("translate", null, "zh-CN"));

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator, speech, ingestion);
        SessionContext ctx = ctx(textSent, binarySent, "fixed");
        ctx.langPair("zh-ja");

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 2, (byte) 1, new byte[]{1, 2}));
        handler.onEndOfUtterance(ctx, eou("t1"));

        verify(speech, never()).startTurn(any(), any(), any());
        verify(orchestrator).translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void autoCounterpartyStreamsAndLocksVisitorLanguage() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TurnSession turn = mock(TurnSession.class);
        when(speech.startTurnAuto(anyList(), eq("ja-JP"), any())).thenReturn(turn);
        when(turn.finish()).thenReturn(new SpeechTranslationResult("你好", "こんにちは", "zh-CN"));

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator, speech, ingestion);
        SessionContext ctx = ctx(textSent, binarySent, "auto");

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 1, (byte) 1, new byte[]{1, 2}));
        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 1, 2L, (byte) 1, (byte) 1, new byte[]{3, 4}));
        handler.onEndOfUtterance(ctx, eou("t1"));

        verify(speech).startTurnAuto(anyList(), eq("ja-JP"), any());
        verify(turn, times(2)).push(any(byte[].class));
        verify(turn).finish();
        assertThat(textSent).anyMatch(m -> "tts_end".equals(m.type()));
        assertThat(textSent).anyMatch(m -> "language_state".equals(m.type())
                && "zh-CN".equals(m.visitorLanguage()) && "locked".equals(m.state()));
    }

    @Test
    void autoWearerFallsBackToAccumulate() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        when(orchestrator.translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new TranslationResult("translate", null, "zh-CN"));

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator, speech, ingestion);
        SessionContext ctx = ctx(textSent, binarySent, "auto");

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 0, (byte) 1, new byte[]{1, 2}));
        handler.onEndOfUtterance(ctx, eou("t1", "wearer"));

        verify(speech, never()).startTurnAuto(anyList(), any(), any());
        verify(orchestrator).translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
    }
}
