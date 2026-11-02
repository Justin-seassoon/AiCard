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
        long t0 = System.currentTimeMillis();
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
        long asrMs = System.currentTimeMillis() - t0;

        // 非日语：跨语言阈值直接检索 + 一次生成日文/本地化答案；未命中再译日重试
        long questionMs = 0;
        long ragMs = 0;
        SkbResult result;
        String answer;
        if (isJapanese(language)) {
            long t = System.currentTimeMillis();
            result = service.answer(question, ctx.customerId(), ctx.storeId(), "skb");
            ragMs = System.currentTimeMillis() - t;
            answer = result.answer();
        } else {
            long t = System.currentTimeMillis();
            SkbService.LocalizedAnswer localized = service.answerLocalized(
                    question, ctx.customerId(), ctx.storeId(), "skb", language);
            result = localized.result();
            ragMs = System.currentTimeMillis() - t;
            if ("no_match".equals(result.status())) {
                long tq = System.currentTimeMillis();
                try {
                    String translated = llm.translate(question, KB_LANGUAGE);
                    if (translated != null && !translated.isBlank() && !translated.equals(question)) {
                        long t2 = System.currentTimeMillis();
                        localized = service.answerLocalized(
                                translated, ctx.customerId(), ctx.storeId(), "skb", language);
                        result = localized.result();
                        ragMs += System.currentTimeMillis() - t2;
                    }
                } catch (ProviderException e) {
                    log.warn("staff_qa question translate failed, keep original result: {}", e.getMessage());
                }
                questionMs = System.currentTimeMillis() - tq;
            }
            answer = localized.localizedAnswer();
        }

        long tTranslate = System.currentTimeMillis();
        if (!isJapanese(language) && (answer == null || answer.isBlank())) {
            // 供应商未返回本地化答案（或 no_match）时回退翻译
            try {
                answer = llm.translate(result.answer(), language);
            } catch (ProviderException e) {
                ctx.sendText(error(ctx, turnId, "E_PROVIDER", "服务暂不可用，稍后再试"));
                return;
            }
            if (answer == null || answer.isBlank()) {
                ctx.sendText(error(ctx, turnId, "E_PROVIDER", "服务暂不可用，稍后再试"));
                return;
            }
        }
        long translateMs = System.currentTimeMillis() - tTranslate;

        ingestion.recordQa(new QaLog(
                ctx.customerId(), ctx.storeId(), ctx.deviceId(), ctx.sessionId(), turnId,
                "skb", question, answer, result.status(),
                result.sourceRefs() == null ? null : String.join(",", result.sourceRefs())));

        // 先回文本，设备先有响应；再合成语音，降低等待超时概率
        ctx.sendText(OutboundMessage.builder()
                .type("answer").sessionId(ctx.sessionId()).turnId(turnId)
                .text(answer).sourceRefs(result.sourceRefs()).build());

        long tTts = System.currentTimeMillis();
        byte[] tts;
        try {
            tts = speech.synthesize(answer, language);
        } catch (ProviderException e) {
            ctx.sendText(error(ctx, turnId, "E_PROVIDER", "服务暂不可用，稍后再试"));
            return;
        }
        long ttsMs = System.currentTimeMillis() - tTts;

        sendTtsChunk(ctx, turnId, tts);
        ctx.sendText(OutboundMessage.builder()
                .type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
        log.info("staff_qa timings: lang={} asr={}ms question={}ms rag={}ms translate={}ms tts={}ms total={}ms",
                language, asrMs, questionMs, ragMs, translateMs, ttsMs, System.currentTimeMillis() - t0);
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
