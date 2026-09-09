package com.aicard.translation.handler;

import com.aicard.gateway.handler.TranslationHandler;
import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.ingestion.model.TranslationSample;
import com.aicard.ingestion.service.IngestionService;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.api.TurnSession;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.service.SkbService;
import com.aicard.translation.audio.BeepAudio;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import com.aicard.translation.orchestrate.TranslationResult;
import com.aicard.translation.qualify.UtteranceQualifier;
import com.aicard.translation.qualify.UtteranceQuality;
import com.aicard.translation.state.TranslationSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 翻译链路处理器：「边说边识别」流式。
 * fixed 模式：按 lang_pair 流式翻译；auto 模式：counterparty（游客）一步 auto-detect 流式，
 * wearer（员工）保持累积（依赖软锁定 visitor_language）。软锁定状态按 session 维护。
 *
 * VKB：auto 模式游客说话翻译成日语后，先撞 VKB 知识库；命中则「嘀嘀」提示服务人员，
 * 服务人员按 OK 确认后答案反向翻译成游客语言播放；未命中/超时则回退播翻译原文。
 * 翻译完成后异步 tee 到旁路采集（不阻塞主链路）。
 */
@Component
public class TranslationHandlerImpl implements TranslationHandler {

    private static final Logger log = LoggerFactory.getLogger(TranslationHandlerImpl.class);

    private final TranslationOrchestrator orchestrator;
    private final SpeechProvider speech;
    private final IngestionService ingestion;
    private final SkbService skbService;
    private final LLMProvider llm;
    private final UtteranceQualifier qualifier;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final Map<String, TranslationSessionState> states = new ConcurrentHashMap<>();
    private final Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
    private final Map<String, TurnSession> fixedTurns = new ConcurrentHashMap<>();
    private final Map<String, TurnSession> autoTurns = new ConcurrentHashMap<>();
    private final Map<String, ByteArrayOutputStream> ttsBuffers = new ConcurrentHashMap<>();
    private final Map<String, PendingVkb> pendingVkbs = new ConcurrentHashMap<>();

    private static final List<String> DEFAULT_CANDIDATES = List.of("ja-JP", "zh-CN", "en-US", "ko-KR");
    private static final int CHUNK_SIZE = 8000;
    private static final long VKB_TIMEOUT_SECONDS = 5;

    public TranslationHandlerImpl(TranslationOrchestrator orchestrator, SpeechProvider speech,
                                  IngestionService ingestion, SkbService skbService, LLMProvider llm,
                                  UtteranceQualifier qualifier) {
        this.orchestrator = orchestrator;
        this.speech = speech;
        this.ingestion = ingestion;
        this.skbService = skbService;
        this.llm = llm;
        this.qualifier = qualifier;
    }

    @Override
    public void onAudioChunk(SessionContext ctx, AudioChunkFrame frame) {
        if ("fixed".equals(ctx.translationMode())) {
            handleFixedChunk(ctx, frame);
        } else if ("auto".equals(ctx.translationMode())) {
            handleAutoChunk(ctx, frame);
        } else {
            buffers.computeIfAbsent(frame.turnId(), t -> new ByteArrayOutputStream())
                    .writeBytes(frame.audio());
        }
    }

    private void handleFixedChunk(SessionContext ctx, AudioChunkFrame frame) {
        TurnSession turn = fixedTurns.get(frame.turnId());
        if (turn != null) {
            turn.push(frame.audio());
            return;
        }
        if (frame.sourceSide() == 0 || frame.sourceSide() == 1) {
            String[] d = direction(frame.sourceSide(), ctx.langPair());
            TurnSession s = speech.startTurn(d[0], d[1], chunk -> sendTtsChunk(ctx, frame.turnId(), chunk));
            fixedTurns.put(frame.turnId(), s);
            s.push(frame.audio());
        } else {
            buffers.computeIfAbsent(frame.turnId(), t -> new ByteArrayOutputStream())
                    .writeBytes(frame.audio());
        }
    }

    private void handleAutoChunk(SessionContext ctx, AudioChunkFrame frame) {
        TurnSession turn = autoTurns.get(frame.turnId());
        if (turn != null) {
            turn.push(frame.audio());
            return;
        }
        // counterparty（游客）：缓存 TTS（先撞 VKB 再决定播原文还是答案）
        if (frame.sourceSide() == 1) {
            ByteArrayOutputStream ttsBuf = new ByteArrayOutputStream();
            ttsBuffers.put(frame.turnId(), ttsBuf);
            TurnSession s = speech.startTurnAuto(DEFAULT_CANDIDATES, ctx.staffLanguage(),
                    ttsBuf::writeBytes);
            autoTurns.put(frame.turnId(), s);
            s.push(frame.audio());
        } else {
            buffers.computeIfAbsent(frame.turnId(), t -> new ByteArrayOutputStream())
                    .writeBytes(frame.audio());
        }
    }

    @Override
    public void onEndOfUtterance(SessionContext ctx, InboundMessage msg) {
        String turnId = msg.turnId();

        // fixed 流式收口（不撞 VKB，直接播）
        if ("fixed".equals(ctx.translationMode()) && fixedTurns.containsKey(turnId)) {
            TurnSession turn = fixedTurns.remove(turnId);
            SpeechTranslationResult result;
            try {
                result = turn.finish();
            } catch (ProviderException e) {
                ctx.sendText(error(ctx, turnId, "E_PROVIDER"));
                return;
            }
            String[] d = fixedDirection(msg.sourceSide(), ctx.langPair());
            recordSample(ctx, msg, result, d[0], d[1], null);
            ctx.sendText(OutboundMessage.builder().type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
            ctx.sendText(OutboundMessage.builder().type("language_state").sessionId(ctx.sessionId()).turnId(turnId)
                    .visitorLanguage("UNKNOWN").state("unknown").build());
            return;
        }

        // auto 流式收口（counterparty）：撞 VKB
        if ("auto".equals(ctx.translationMode()) && autoTurns.containsKey(turnId)) {
            TurnSession turn = autoTurns.remove(turnId);
            SpeechTranslationResult result;
            try {
                result = turn.finish();
            } catch (ProviderException e) {
                ctx.sendText(error(ctx, turnId, "E_PROVIDER"));
                return;
            }
            byte[] originalTts = extractTts(turnId);
            String visitor = result.detectedLanguage() != null && !result.detectedLanguage().isBlank()
                    ? result.detectedLanguage() : "UNKNOWN";
            UtteranceQuality quality = qualifier.qualify(result.finalText(), msg.utteranceDurationMs());
            // visitor_language 仅用于员工(wearer)方向的翻译目标语言；游客方向走一步 auto-detect 不读记忆。
            // 已知局限（联调后评估防漂移）：换游客不重置记忆、同一游客夹杂语言时员工方向会漂移。
            if (!"UNKNOWN".equals(visitor) && quality == UtteranceQuality.NORMAL) {
                states.computeIfAbsent(ctx.sessionId(), s -> new TranslationSessionState()).lock(visitor);
            }
            if (quality != UtteranceQuality.NORMAL) {
                log.info("quality gate filtered: session={} turn={} quality={} text={} durMs={}",
                        ctx.sessionId(), turnId, quality, result.finalText(), msg.utteranceDurationMs());
            }
            recordSample(ctx, msg, result, result.detectedLanguage(), ctx.staffLanguage(), null);

            // 撞 VKB（游客语言已确定才撞）
            boolean vkbHit = false;
            if (!"UNKNOWN".equals(visitor)) {
                SkbResult vkb = tryVkb(result.translatedText(), ctx);
                if (vkb != null && "ok".equals(vkb.status())) {
                    sendTtsChunk(ctx, turnId, BeepAudio.beepBeep());
                    ScheduledFuture<?> timeout = scheduler.schedule(
                            () -> timeoutVkb(ctx.sessionId()), VKB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    pendingVkbs.put(ctx.sessionId(),
                            new PendingVkb(ctx, turnId, vkb.answer(), originalTts, visitor, timeout));
                    vkbHit = true;
                }
            }

            if (!vkbHit) {
                sendTtsChunk(ctx, turnId, originalTts);
                ctx.sendText(OutboundMessage.builder().type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
            }
            ctx.sendText(OutboundMessage.builder().type("language_state").sessionId(ctx.sessionId()).turnId(turnId)
                    .visitorLanguage(visitor).state("UNKNOWN".equals(visitor) ? "unknown" : "locked").build());
            return;
        }

        // 累积路径（auto wearer/uncertain + fixed 兜底）
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
    public void onButtonEvent(SessionContext ctx, InboundMessage msg) {
        if (!"ok".equals(msg.button())) {
            return;
        }
        PendingVkb p = pendingVkbs.remove(ctx.sessionId());
        if (p == null) {
            return;
        }
        p.timeout.cancel(false);
        try {
            String translatedAnswer = llm.translate(p.answer, p.visitorLang);
            byte[] tts = speech.synthesize(translatedAnswer, p.visitorLang);
            sendTtsChunk(ctx, p.turnId, tts);
        } catch (Exception e) {
            // 答案翻译失败，回退播原文
            sendTtsChunk(ctx, p.turnId, p.originalTts);
        }
        ctx.sendText(OutboundMessage.builder().type("tts_end").sessionId(ctx.sessionId()).turnId(p.turnId).build());
    }

    @Override
    public void onStopTts(SessionContext ctx, InboundMessage msg) {
        TurnSession turn = fixedTurns.remove(msg.turnId());
        if (turn == null) {
            turn = autoTurns.remove(msg.turnId());
        }
        if (turn != null) {
            turn.cancel();
        }
        ttsBuffers.remove(msg.turnId());
        buffers.remove(msg.turnId());
        PendingVkb p = pendingVkbs.get(ctx.sessionId());
        if (p != null && p.turnId.equals(msg.turnId())) {
            pendingVkbs.remove(ctx.sessionId());
            p.timeout.cancel(false);
        }
    }

    @Override
    public void onTranslationModeChange(SessionContext ctx) {
        states.remove(ctx.sessionId());
    }

    private void timeoutVkb(String sessionId) {
        PendingVkb p = pendingVkbs.remove(sessionId);
        if (p != null) {
            sendTtsChunk(p.ctx, p.turnId, p.originalTts);
            p.ctx.sendText(OutboundMessage.builder().type("tts_end")
                    .sessionId(p.ctx.sessionId()).turnId(p.turnId).build());
        }
    }

    private SkbResult tryVkb(String question, SessionContext ctx) {
        try {
            return skbService.answer(question, ctx.customerId(), ctx.storeId(), "vkb");
        } catch (Exception e) {
            return null; // 撞 VKB 失败，降级为播原文
        }
    }

    private byte[] extract(String turnId) {
        ByteArrayOutputStream b = buffers.remove(turnId);
        return b == null ? new byte[0] : b.toByteArray();
    }

    private byte[] extractTts(String turnId) {
        ByteArrayOutputStream b = ttsBuffers.remove(turnId);
        return b == null ? new byte[0] : b.toByteArray();
    }

    private void recordSample(SessionContext ctx, InboundMessage msg, SpeechTranslationResult result,
                              String srcLang, String tgtLang, Double lidConfidence) {
        ingestion.record(new TranslationSample(
                ctx.customerId(), ctx.storeId(), ctx.sessionId(), msg.turnId(),
                srcLang, tgtLang, result.finalText(), result.translatedText(),
                lidConfidence, msg.sourceSide()));
    }

    private static OutboundMessage error(SessionContext ctx, String turnId, String code) {
        return OutboundMessage.builder().type("error").sessionId(ctx.sessionId()).turnId(turnId)
                .code(code).message("please repeat").build();
    }

    /** fixed 模式方向：sourceSide 0=wearer(员工) 1=counterparty(游客)，lang_pair 形如 "zh-ja"。 */
    private static String[] direction(byte sourceSide, String langPair) {
        String[] pair = langPair == null ? new String[]{"zh", "ja"} : langPair.split("-");
        if (pair.length < 2) {
            pair = new String[]{"zh", "ja"};
        }
        String src = sourceSide == 0 ? pair[1] : pair[0];
        String tgt = sourceSide == 0 ? pair[0] : pair[1];
        return new String[]{src, tgt};
    }

    /** fixed 模式方向（String sourceSide 版，供旁路采集用）。 */
    private static String[] fixedDirection(String sourceSide, String langPair) {
        String[] pair = langPair == null ? new String[]{"zh", "ja"} : langPair.split("-");
        if (pair.length < 2) {
            pair = new String[]{"zh", "ja"};
        }
        String src = "wearer".equals(sourceSide) ? pair[1] : pair[0];
        String tgt = "wearer".equals(sourceSide) ? pair[0] : pair[1];
        return new String[]{src, tgt};
    }

    /** 单个 TTS 音频块按 ≤8KB 分片下发（接口规范 §9 单帧上限）。 */
    private void sendTtsChunk(SessionContext ctx, String turnId, byte[] chunk) {
        for (int off = 0; off < chunk.length; off += CHUNK_SIZE) {
            int len = Math.min(CHUNK_SIZE, chunk.length - off);
            ctx.sendTtsAudio(new TtsAudioFrame(turnId, Arrays.copyOfRange(chunk, off, off + len)));
        }
    }

    /** VKB 待确认状态：命中后等待服务人员按 OK。 */
    private static final class PendingVkb {
        final SessionContext ctx;
        final String turnId;
        final String answer;        // VKB 答案（日语）
        final byte[] originalTts;   // 翻译原文 TTS（超时回退播它）
        final String visitorLang;   // 游客语言
        final ScheduledFuture<?> timeout;

        PendingVkb(SessionContext ctx, String turnId, String answer, byte[] originalTts,
                   String visitorLang, ScheduledFuture<?> timeout) {
            this.ctx = ctx;
            this.turnId = turnId;
            this.answer = answer;
            this.originalTts = originalTts;
            this.visitorLang = visitorLang;
            this.timeout = timeout;
        }
    }
}
