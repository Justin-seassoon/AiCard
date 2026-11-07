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
import com.aicard.provider.api.TranscriptionSession;
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
 * 员工知识库问答处理器（P5）：缓存音频 → ASR(staff_language) → 受控 RAG → TTS 流式分片下发。
 * 替换联调桩 StubSkbHandler。答案文本先回 answer 帧，TTS 边合成边按 ≤8KB 分片下发（接口规范 §2.2/§5.2）；
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
    private final ConcurrentHashMap<String, TurnAsr> asrTurns = new ConcurrentHashMap<>();
    /** sessionId → 当前 turnId 的映射：新 turn 到来时取消上一轮残留的识别会话，避免 Azure 会话泄漏。 */
    private final ConcurrentHashMap<String, String> currentTurn = new ConcurrentHashMap<>();

    public SkbHandlerImpl(SpeechProvider speech, SkbService service, IngestionService ingestion,
                          LLMProvider llm) {
        this.speech = speech;
        this.service = service;
        this.ingestion = ingestion;
        this.llm = llm;
    }

    @Override
    public void onAudioChunk(SessionContext ctx, AudioChunkFrame frame) {
        String key = turnKey(ctx, frame.turnId());
        String previous = currentTurn.put(ctx.sessionId(), key);
        if (previous != null && !previous.equals(key)) {
            TurnAsr stale = asrTurns.remove(previous);
            if (stale != null && stale.session != null) {
                try {
                    stale.session.cancel();
                } catch (RuntimeException ignored) {
                    // 上一轮会话已失效
                }
            }
        }
        TurnAsr turn = asrTurns.computeIfAbsent(key, k -> new TurnAsr());
        if (!turn.fallback) {
            if (turn.session == null) {
                try {
                    turn.session = speech.startTranscription(normalizeLanguage(ctx.staffLanguage()));
                } catch (RuntimeException e) {
                    log.warn("staff_qa streaming asr unavailable, fallback to buffer: {}", e.getMessage());
                }
                if (turn.session == null) {
                    turn.fallback = true;
                }
            }
            if (turn.session != null) {
                try {
                    turn.session.push(frame.audio());
                    return;
                } catch (RuntimeException e) {
                    log.warn("staff_qa streaming asr push failed, fallback to buffer: {}", e.getMessage());
                    try {
                        turn.session.cancel();
                    } catch (RuntimeException ignored) {
                        // 会话已失效
                    }
                    turn.session = null;
                    turn.fallback = true;
                }
            }
        }
        turn.buffer.writeBytes(frame.audio());
    }

    @Override
    public void onEndOfUtterance(SessionContext ctx, InboundMessage msg) {
        long t0 = System.currentTimeMillis();
        String turnId = msg.turnId();
        TurnAsr turn = asrTurns.remove(turnKey(ctx, turnId));
        currentTurn.remove(ctx.sessionId(), turnKey(ctx, turnId));
        String language = normalizeLanguage(ctx.staffLanguage());

        String question;
        try {
            question = turn != null && turn.session != null
                    ? turn.session.finish()
                    : speech.transcribe(turn == null ? new byte[0] : turn.buffer.toByteArray(), language);
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
        TtsChunkStreamer streamer = new TtsChunkStreamer(ctx, turnId, tTts);
        try {
            speech.synthesizeStream(answer, language, streamer::accept);
        } catch (ProviderException e) {
            ctx.sendText(error(ctx, turnId, "E_PROVIDER", "服务暂不可用，稍后再试"));
            return;
        }
        long firstChunkMs = streamer.finish();
        long ttsMs = System.currentTimeMillis() - tTts;

        ctx.sendText(OutboundMessage.builder()
                .type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
        log.info("staff_qa timings: lang={} asr={}ms question={}ms rag={}ms translate={}ms tts_first={}ms tts={}ms total={}ms",
                language, asrMs, questionMs, ragMs, translateMs, firstChunkMs, ttsMs, System.currentTimeMillis() - t0);
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.isBlank() ? KB_LANGUAGE : language.trim();
    }

    private static boolean isJapanese(String language) {
        String l = language.toLowerCase(Locale.ROOT);
        return "ja".equals(l) || l.startsWith("ja-");
    }

    private static String turnKey(SessionContext ctx, String turnId) {
        return ctx.sessionId() + "/" + turnId;
    }

    /** 一轮问答的音频侧状态：优先流式 ASR，启动失败时退回整段缓存后一次性识别。 */
    private static final class TurnAsr {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private TranscriptionSession session;
        private boolean fallback;
    }

    /** 流式 TTS 分片下发：累积供应商回调的音频，按 ≤8KB 切帧边收边发（接口规范 §9 单帧上限）。 */
    private static final class TtsChunkStreamer {
        private final SessionContext ctx;
        private final String turnId;
        private final long startedAt;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private long firstChunkMs = -1;

        TtsChunkStreamer(SessionContext ctx, String turnId, long startedAt) {
            this.ctx = ctx;
            this.turnId = turnId;
            this.startedAt = startedAt;
        }

        synchronized void accept(byte[] chunk) {
            if (chunk == null || chunk.length == 0) {
                return;
            }
            if (firstChunkMs < 0) {
                firstChunkMs = System.currentTimeMillis() - startedAt;
            }
            pending.writeBytes(chunk);
            while (pending.size() >= CHUNK_SIZE) {
                byte[] all = pending.toByteArray();
                sendChunk(Arrays.copyOfRange(all, 0, CHUNK_SIZE));
                pending.reset();
                pending.write(all, CHUNK_SIZE, all.length - CHUNK_SIZE);
            }
        }

        /** 下发不足一帧的余量，返回首片时延（毫秒；未产生音频时为 -1）。 */
        synchronized long finish() {
            if (pending.size() > 0) {
                sendChunk(pending.toByteArray());
                pending.reset();
            }
            return firstChunkMs;
        }

        private void sendChunk(byte[] chunk) {
            ctx.sendTtsAudio(new TtsAudioFrame(turnId, chunk));
        }
    }

    private OutboundMessage error(SessionContext ctx, String turnId, String code, String message) {
        return OutboundMessage.builder()
                .type("error").sessionId(ctx.sessionId()).turnId(turnId)
                .code(code).message(message).build();
    }
}
