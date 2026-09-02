package com.aicard.gateway.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.session.SessionContext;
import org.springframework.stereotype.Component;

/**
 * 网关消息路由：按帧类型分发（二进制音频 / JSON 控制），并按 scope 路由到
 * 翻译或问答 handler。scope / translation_mode 的会话内切换在此落地（见接口规范 §1.4）。
 */
@Component
public class GatewayWebSocketHandler {

    private final TranslationHandler translation;
    private final SkbHandler skb;

    public GatewayWebSocketHandler(TranslationHandler translation, SkbHandler skb) {
        this.translation = translation;
        this.skb = skb;
    }

    public void handleAudio(SessionContext ctx, AudioChunkFrame frame) {
        ctx.turnId(frame.turnId());
        if (isTranslate(ctx)) {
            translation.onAudioChunk(ctx, frame);
        } else {
            skb.onAudioChunk(ctx, frame);
        }
    }

    public void handleControl(SessionContext ctx, InboundMessage msg) {
        if (msg.turnId() != null) {
            ctx.turnId(msg.turnId());
        }
        switch (msg.type()) {
            case "scope_change" -> ctx.scope(msg.scope());
            case "translation_mode_change" -> {
                ctx.translationMode(msg.translationMode());
                ctx.langPair(msg.langPair());
                // 接口规范 §1.4：模式切换清 visitor_language（软锁定状态由 TranslationHandler 维护）
                translation.onTranslationModeChange(ctx);
            }
            case "eou" -> routeEou(ctx, msg);
            case "stop_tts" -> {
                // staff_qa 的 stop_tts 停止答案播放由 P5 处理，此处仅翻译链路
                if (isTranslate(ctx)) {
                    translation.onStopTts(ctx, msg);
                }
            }
            default -> { /* sleep_notice / button_event / playback_event / headset_event / config_pull 由 transport 层处理 */ }
        }
    }

    private void routeEou(SessionContext ctx, InboundMessage msg) {
        if (isTranslate(ctx)) {
            translation.onEndOfUtterance(ctx, msg);
        } else {
            skb.onEndOfUtterance(ctx, msg);
        }
    }

    private boolean isTranslate(SessionContext ctx) {
        return !"staff_qa".equals(ctx.scope());
    }
}
