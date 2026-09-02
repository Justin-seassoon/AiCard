package com.aicard.translation.handler;

import com.aicard.gateway.handler.TranslationHandler;
import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import com.aicard.translation.orchestrate.TranslationResult;
import com.aicard.translation.state.TranslationSessionState;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 翻译链路处理器：按 turn_id 累积音频，eou 触发编排并回发 tts_audio/error，
 * stop_tts 停旧 TTS（保留会话与语言记忆）。软锁定状态按 session 维护。
 */
@Component
public class TranslationHandlerImpl implements TranslationHandler {

    private final TranslationOrchestrator orchestrator;
    private final ConcurrentHashMap<String, TranslationSessionState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();

    private static final List<String> DEFAULT_CANDIDATES = List.of("ja-JP", "zh-CN", "en-US", "ko-KR");

    public TranslationHandlerImpl(TranslationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void onAudioChunk(SessionContext ctx, AudioChunkFrame frame) {
        buffers.computeIfAbsent(frame.turnId(), t -> new ByteArrayOutputStream())
                .writeBytes(frame.audio());
    }

    @Override
    public void onEndOfUtterance(SessionContext ctx, InboundMessage msg) {
        String turnId = msg.turnId();
        byte[] audio = extract(turnId);
        TranslationSessionState state = states.computeIfAbsent(ctx.sessionId(), s -> new TranslationSessionState());
        TranslationResult r = orchestrator.translateUtterance(
                audio, msg.sourceSide(), ctx.translationMode(), ctx.langPair(), false, state,
                DEFAULT_CANDIDATES, ctx.staffLanguage(),
                chunk -> sendTtsChunk(ctx, turnId, chunk));

        if ("translate".equals(r.action())) {
            ctx.sendText(OutboundMessage.builder().type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
            String stateStr = "UNKNOWN".equals(r.newVisitorLanguage()) ? "unknown" : "locked";
            ctx.sendText(OutboundMessage.builder().type("language_state").sessionId(ctx.sessionId()).turnId(turnId)
                    .visitorLanguage(r.newVisitorLanguage()).state(stateStr).build());
        } else {
            String code = r.errorCode() != null ? r.errorCode() : "E_PROVIDER";
            ctx.sendText(OutboundMessage.builder().type("error").sessionId(ctx.sessionId()).turnId(turnId)
                    .code(code).message("please repeat").fallbackAction("ask_repeat").build());
        }
    }

    @Override
    public void onStopTts(SessionContext ctx, InboundMessage msg) {
        // 释放本轮缓冲，保留会话与 visitor_language 记忆（spec §6.6）
        buffers.remove(msg.turnId());
    }

    @Override
    public void onTranslationModeChange(SessionContext ctx) {
        // 语言对模式切换清软锁定记忆（接口规范 §1.4）
        states.remove(ctx.sessionId());
    }

    private byte[] extract(String turnId) {
        ByteArrayOutputStream b = buffers.remove(turnId);
        return b == null ? new byte[0] : b.toByteArray();
    }

    /** 单个 TTS 音频块按 ≤8KB 分片下发（接口规范 §9 单帧上限）。 */
    private void sendTtsChunk(SessionContext ctx, String turnId, byte[] chunk) {
        int chunkSize = 8000;
        for (int off = 0; off < chunk.length; off += chunkSize) {
            int len = Math.min(chunkSize, chunk.length - off);
            ctx.sendTtsAudio(new TtsAudioFrame(turnId, Arrays.copyOfRange(chunk, off, off + len)));
        }
    }
}
