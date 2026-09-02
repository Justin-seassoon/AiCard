package com.aicard.translation.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import com.aicard.translation.orchestrate.TranslationResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranslationHandlerImplTest {

    private static InboundMessage eou(String turnId) {
        return InboundMessage.builder().type("eou").sessionId("s1").turnId(turnId)
                .sourceSide("counterparty").build();
    }

    private static SessionContext ctx(CopyOnWriteArrayList<OutboundMessage> text,
                                      CopyOnWriteArrayList<TtsAudioFrame> binary) {
        return new SessionContext("s1", "dev-1", 1L, 2L, "translate", "auto", "ja-JP",
                text::add, binary::add);
    }

    @Test
    void accumulatesAudioAndSendsTtsOnEou() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        when(orchestrator.translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<byte[]> onTts = invocation.getArgument(8);
                    onTts.accept(new byte[]{5, 6});
                    return new TranslationResult("translate", null, "zh-CN");
                });

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator);
        SessionContext ctx = ctx(textSent, binarySent);

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 1, (byte) 1, new byte[]{1, 2}));
        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 1, 2L, (byte) 1, (byte) 1, new byte[]{3, 4}));
        handler.onEndOfUtterance(ctx, eou("t1"));

        assertThat(binarySent).anyMatch(f -> f.turnId().equals("t1") && f.audio().length == 2);
        assertThat(textSent).anyMatch(m -> "tts_end".equals(m.type()));
        assertThat(textSent).anyMatch(m -> "language_state".equals(m.type())
                && "zh-CN".equals(m.visitorLanguage()));
    }

    @Test
    void lowConfidenceSendsError() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        when(orchestrator.translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new TranslationResult("repeat", "E_LID_LOW", "UNKNOWN"));

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator);
        SessionContext ctx = ctx(textSent, binarySent);

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 1, (byte) 1, new byte[]{1, 2}));
        handler.onEndOfUtterance(ctx, eou("t1"));

        assertThat(textSent).anyMatch(m -> "error".equals(m.type()) && "E_LID_LOW".equals(m.code()));
    }
}
