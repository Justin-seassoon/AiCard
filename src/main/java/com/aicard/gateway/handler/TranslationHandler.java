package com.aicard.gateway.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.session.SessionContext;

/**
 * 翻译链路处理器接口（P4 实现真实逻辑；联调阶段由 StubTranslationHandler 兜底）。
 */
public interface TranslationHandler {
    void onAudioChunk(SessionContext ctx, AudioChunkFrame frame);
    void onEndOfUtterance(SessionContext ctx, InboundMessage msg);
    void onStopTts(SessionContext ctx, InboundMessage msg);
    /** 语言对模式切换（translation_mode_change）：清空软锁定语言记忆（接口规范 §1.4）。 */
    void onTranslationModeChange(SessionContext ctx);
    /** 按键事件（VKB 候选确认 button:ok）。 */
    void onButtonEvent(SessionContext ctx, InboundMessage msg);
}
