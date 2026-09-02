package com.aicard.gateway.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 员工知识库问答联调桩：收到 eou 后回 answer + 固定 TTS 音频 + tts_end。
 * P5 落地真实 SkbHandler 后移除本类。
 */
@Component
public class StubSkbHandler implements SkbHandler {

    @Override
    public void onAudioChunk(SessionContext ctx, AudioChunkFrame frame) {
        // stub 不做 ASR，忽略分片
    }

    @Override
    public void onEndOfUtterance(SessionContext ctx, InboundMessage msg) {
        String turnId = ctx.turnId() != null ? ctx.turnId() : msg.turnId();
        ctx.sendText(OutboundMessage.builder()
                .type("answer").sessionId(ctx.sessionId()).turnId(turnId)
                .text("[stub] 员工知识库答案")
                .sourceRefs(List.of("demo@v1#1"))
                .build());
        ctx.sendTtsAudio(new TtsAudioFrame(turnId, StubTts.beep()));
        ctx.sendText(OutboundMessage.builder()
                .type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
    }
}
