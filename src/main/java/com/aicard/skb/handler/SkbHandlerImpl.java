package com.aicard.skb.handler;

import com.aicard.gateway.handler.SkbHandler;
import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.ingestion.model.QaLog;
import com.aicard.ingestion.service.IngestionService;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.service.SkbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 员工知识库问答处理器（P5）：缓存音频 → ASR(staff_language) → 受控 RAG → TTS 分片下发。
 * 替换联调桩 StubSkbHandler。答案 TTS 一次性合成后按 ≤8KB 分片（接口规范 §2.2/§5.2）；
 * 无依据命中回「暂无明确说明」（answer 帧，非 error）。
 *
 * <p>多语言：知识库为日语，员工语言由 {@code staff_language} 决定。非日语员工的问题先翻译成日语再检索，
 * 回答校验通过后再翻译回员工语言并用对应音色播报；日语员工走原链路，不增加额外调用。</p>
 */
@Component
public class SkbHandlerImpl implements SkbHandler {

    private static final Logger log = LoggerFactory.getLogger(SkbHandlerImpl.class);
    private static final int CHUNK_SIZE = 8000;
    private static final String KB_LANGUAGE = "ja-JP";

    private final SpeechProvider speech;
    private final SkbService service;
    private final IngestionService ingestion;
    private final LLMProvider llm;
    private final ConcurrentHashMap<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();

    public SkbHandlerImpl(SpeechProvider speech, SkbService service, IngestionService ingestion,
                          LLMProvider llm) {
        this.speech = speech;
        this.service = service;
        this.ingestion = ingestion;
        this.llm = llm;
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
        String language = normalizeLanguage(ctx.staffLanguage());

        String question;
        try {
            question = speech.transcribe(audio, language);
        } catch (ProviderException e) {
            ctx.sendText(error(ctx, turnId, "E_ASR_FAIL", "请再说一遍"));
            return;
        }

        // 知识库是日语：非日语员工的问题先翻译成日语再检索，保持检索/校验口径不变
        String searchQuestion = question;
        if (!isJapanese(language)) {
            try {
                String translated = llm.translate(question, KB_LANGUAGE);
                if (translated != null && !translated.isBlank()) {
                    searchQuestion = translated;
                }
            } catch (ProviderException e) {
                log.warn("staff_qa question translate failed, fallback to original: {}", e.getMessage());
            }
        }

        SkbResult result = service.answer(searchQuestion, ctx.customerId(), ctx.storeId(), "skb");

        // 回答仍是日语；校验通过后翻译回员工语言
        String answer = result.answer();
        if (!isJapanese(language)) {
            try {
                answer = llm.translate(answer, language);
            } catch (ProviderException e) {
                ctx.sendText(error(ctx, turnId, "E_PROVIDER", "服务暂不可用，稍后再试"));
                return;
            }
            if (answer == null || answer.isBlank()) {
                ctx.sendText(error(ctx, turnId, "E_PROVIDER", "服务暂不可用，稍后再试"));
                return;
            }
        }

        ingestion.recordQa(new QaLog(
                ctx.customerId(), ctx.storeId(), ctx.deviceId(), ctx.sessionId(), turnId,
                "skb", question, answer, result.status(),
                result.sourceRefs() == null ? null : String.join(",", result.sourceRefs())));

        byte[] tts;
        try {
            tts = speech.synthesize(answer, language);
        } catch (ProviderException e) {
            ctx.sendText(error(ctx, turnId, "E_PROVIDER", "服务暂不可用，稍后再试"));
            return;
        }

        // answer(文本 + source_refs) → TTS 分片 → tts_end（接口规范 §5.2）
        ctx.sendText(OutboundMessage.builder()
                .type("answer").sessionId(ctx.sessionId()).turnId(turnId)
                .text(answer).sourceRefs(result.sourceRefs()).build());
        sendTtsChunk(ctx, turnId, tts);
        ctx.sendText(OutboundMessage.builder()
                .type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.isBlank() ? KB_LANGUAGE : language.trim();
    }

    private static boolean isJapanese(String language) {
        String l = language.toLowerCase(Locale.ROOT);
        return "ja".equals(l) || l.startsWith("ja-");
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
