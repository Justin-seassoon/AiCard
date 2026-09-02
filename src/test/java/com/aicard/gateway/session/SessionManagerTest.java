package com.aicard.gateway.session;

import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SessionManagerTest {

    @Test
    void createsAndGetsSession() {
        SessionManager m = new SessionManager();
        SessionContext ctx = m.create("s1", "dev-1", 1L, 2L, "translate", "auto", "ja-JP", x -> {}, f -> {});
        assertThat(m.get("s1")).isPresent().get().isSameAs(ctx);
        assertThat(ctx.scope()).isEqualTo("translate");
        assertThat(ctx.translationMode()).isEqualTo("auto");
    }

    @Test
    void sendTextAndSendTtsInvokeSenders() {
        SessionManager m = new SessionManager();
        AtomicReference<String> textType = new AtomicReference<>();
        AtomicReference<byte[]> binary = new AtomicReference<>();
        SessionContext ctx = m.create("s1", "dev-1", 1L, 2L, "translate", "auto", "ja-JP",
                out -> textType.set(out.type()),
                frame -> binary.set(frame.audio()));

        ctx.sendText(OutboundMessage.builder().type("error").sessionId("s1").code("E_NET").build());
        ctx.sendTtsAudio(new TtsAudioFrame("t1", new byte[]{1, 2}));

        assertThat(textType.get()).isEqualTo("error");
        assertThat(binary.get()).containsExactly(1, 2);
    }

    @Test
    void removeDropsSession() {
        SessionManager m = new SessionManager();
        m.create("s1", "dev-1", 1L, 2L, "translate", "auto", "ja-JP", x -> {}, f -> {});
        m.remove("s1");
        assertThat(m.get("s1")).isEmpty();
    }
}
