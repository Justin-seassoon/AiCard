package com.aicard.skb.handler;

import com.aicard.gateway.handler.SkbHandler;
import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.service.SkbService;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 员工知识库问答处理器（P5）：缓存音频 → ASR(staff_language) → 受控 RAG → TTS 分片下发。
 * 替换联调桩 StubSkbHandler。答案 TTS 一次性合成后按 ≤8KB 分片（接口规范 §2.2/§5.2）；
 * 无依据命中回「暂无明确说明」（answer 帧，非 error）。
 */
@Component
public class SkbHandlerImpl implements SkbHandler {

    private static final int CHUNK_SIZE = 8000;

    private final SpeechProvider speech;
    private final SkbService service;
    private final ConcurrentHashMap<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();

    public SkbHandlerImpl(SpeechProvider speech, SkbService service) {
        this.speech = speech;
        this.service = service;
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

        String question;
        try {
            question = speech.transcribe(audio, ctx.staffLanguage());
        } catch (ProviderException e) {
            ctx.sendText(error(ctx, turnId, "E_ASR_FAIL", "请再说一遍"));
            return;
        }

        SkbResult result = service.answer(question, ctx.customerId(), ctx.storeId());

        byte[] tts;
        try {
            tts = speech.synthesize(result.answer(), ctx.staffLanguage());
        } catch (ProviderException e) {
            ctx.sendText(error(ctx, turnId, "E_PROVIDER", "服务暂不可用，稍后再试"));
            return;
        }

        // answer(文本 + source_refs) → TTS 分片 → tts_end（接口规范 §5.2）
        ctx.sendText(OutboundMessage.builder()
                .type("answer").sessionId(ctx.sessionId()).turnId(turnId)
                .text(result.answer()).sourceRefs(result.sourceRefs()).build());
        sendTtsChunk(ctx, turnId, tts);
        ctx.sendText(OutboundMessage.builder()
                .type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
    }

    private byte[] extract(String turnId) {
        ByteArrayOutputStream b = buffers.remove(turnId);
        return b == null ? new byte[0] : b.toByteArray();
    }

    /** 单个 TTS 音频块按 ≤8KB 分片下发（接口规范 §9 单帧上限）。 */
    private void sendTtsChunk(SessionContext ctx, String turnId, byte[] chunk) {
        for (int off = 0; off < chunk.length; off += CHUNK_SIZE) {
            int len = Math.min(CHUNK_SIZE, chunk.length - off);
            ctx.sendTtsAudio(new TtsAudioFrame(turnId, Arrays.copyOfRange(chunk, off, off + len)));
        }
    }

    private OutboundMessage error(SessionContext ctx, String turnId, String code, String message) {
        return OutboundMessage.builder()
                .type("error").sessionId(ctx.sessionId()).turnId(turnId)
                .code(code).message(message).build();
    }
}
