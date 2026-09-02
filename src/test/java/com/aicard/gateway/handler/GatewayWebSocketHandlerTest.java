package com.aicard.gateway.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.session.SessionContext;
import com.aicard.gateway.session.SessionManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GatewayWebSocketHandlerTest {

    private final TranslationHandler translation = mock(TranslationHandler.class);
    private final SkbHandler skb = mock(SkbHandler.class);
    private final SessionManager sessions = new SessionManager();

    private GatewayWebSocketHandler handler() {
        return new GatewayWebSocketHandler(translation, skb);
    }

    private SessionContext ctx(String scope) {
        return sessions.create("s1", "dev-1", 1L, 2L, scope, "auto", "ja-JP", m -> {}, f -> {});
    }

    @Test
    void routesAudioToTranslationWhenScopeTranslate() {
        SessionContext ctx = ctx("translate");
        AudioChunkFrame frame = new AudioChunkFrame("t9", 1, 1L, (byte) 1, (byte) 1, new byte[]{1});

        handler().handleAudio(ctx, frame);

        verify(translation).onAudioChunk(ctx, frame);
        verifyNoInteractions(skb);
        assertThat(ctx.turnId()).isEqualTo("t9");
    }

    @Test
    void routesAudioToSkbWhenScopeStaffQa() {
        SessionContext ctx = ctx("staff_qa");
        AudioChunkFrame frame = new AudioChunkFrame("t9", 1, 1L, (byte) 1, (byte) 1, new byte[]{1});

        handler().handleAudio(ctx, frame);

        verify(skb).onAudioChunk(ctx, frame);
        verifyNoInteractions(translation);
    }

    @Test
    void routesEouToTranslation() {
        SessionContext ctx = ctx("translate");
        InboundMessage eou = InboundMessage.builder().type("eou").sessionId("s1").turnId("t9").build();

        handler().handleControl(ctx, eou);

        verify(translation).onEndOfUtterance(ctx, eou);
    }

    @Test
    void scopeChangeUpdatesScope() {
        SessionContext ctx = ctx("translate");
        InboundMessage change = InboundMessage.builder().type("scope_change").sessionId("s1").scope("staff_qa").build();

        handler().handleControl(ctx, change);

        assertThat(ctx.scope()).isEqualTo("staff_qa");
    }

    @Test
    void translationModeChangeNotifiesHandler() {
        SessionContext ctx = ctx("translate");
        InboundMessage change = InboundMessage.builder().type("translation_mode_change").sessionId("s1")
                .translationMode("fixed").langPair("zh-ja").build();

        handler().handleControl(ctx, change);

        assertThat(ctx.translationMode()).isEqualTo("fixed");
        verify(translation).onTranslationModeChange(ctx);
    }
}
