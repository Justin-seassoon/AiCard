package com.aicard.gateway.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.session.SessionContext;

/**
 * 员工知识库问答处理器接口（P5 实现真实逻辑；联调阶段由 StubSkbHandler 兜底）。
 */
public interface SkbHandler {
    void onAudioChunk(SessionContext ctx, AudioChunkFrame frame);
    void onEndOfUtterance(SessionContext ctx, InboundMessage msg);
}
