package com.aicard.translation.handler;

import com.aicard.gateway.handler.TranslationHandler;
import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.api.TurnSession;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import com.aicard.translation.orchestrate.TranslationResult;
import com.aicard.translation.state.TranslationSessionState;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 翻译链路处理器：「边说边识别」流式。
 * fixed 模式：按 lang_pair 流式翻译；auto 模式：counterparty（游客）一步 auto-detect 流式，
 * wearer（员工）保持累积（依赖软锁定 visitor_language）。软锁定状态按 session 维护。
 */
@Component
public class TranslationHandlerImpl implements TranslationHandler {

    private final TranslationOrchestrator orchestrator;
    private final SpeechProvider speech;
    private final ConcurrentHashMap<String, TranslationSessionState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TurnSession> fixedTurns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TurnSession> autoTurns = new ConcurrentHashMap<>();

    private static final List<String> DEFAULT_CANDIDATES = List.of("ja-JP", "zh-CN", "en-US", "ko-KR");
    private static final int CHUNK_SIZE = 8000;

    public TranslationHandlerImpl(TranslationOrchestrator orchestrator, SpeechProvider speech) {
        this.orchestrator = orchestrator;
        this.speech = speech;
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
        // 未启动：用首个 sourceSide 确定的帧定方向启动流式；uncertain 帧先累积
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
        // 未启动：counterparty（游客）→ 一步 auto-detect；wearer/uncertain → 累积
        if (frame.sourceSide() == 1) {
            TurnSession s = speech.startTurnAuto(DEFAULT_CANDIDATES, ctx.staffLanguage(),
                    chunk -> sendTtsChunk(ctx, frame.turnId(), chunk));
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

        // fixed 流式收口
        if ("fixed".equals(ctx.translationMode()) && fixedTurns.containsKey(turnId)) {
            TurnSession turn = fixedTurns.remove(turnId);
            try {
                turn.finish();
            } catch (ProviderException e) {
                ctx.sendText(OutboundMessage.builder().type("error").sessionId(ctx.sessionId()).turnId(turnId)
                        .code("E_PROVIDER").message("please repeat").build());
                return;
            }
            ctx.sendText(OutboundMessage.builder().type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
            ctx.sendText(OutboundMessage.builder().type("language_state").sessionId(ctx.sessionId()).turnId(turnId)
                    .visitorLanguage("UNKNOWN").state("unknown").build());
            return;
        }

        // auto 流式收口（counterparty）
        if ("auto".equals(ctx.translationMode()) && autoTurns.containsKey(turnId)) {
            TurnSession turn = autoTurns.remove(turnId);
            SpeechTranslationResult result;
            try {
                result = turn.finish();
            } catch (ProviderException e) {
                ctx.sendText(OutboundMessage.builder().type("error").sessionId(ctx.sessionId()).turnId(turnId)
                        .code("E_PROVIDER").message("please repeat").build());
                return;
            }
            String visitor = result.detectedLanguage() != null && !result.detectedLanguage().isBlank()
                    ? result.detectedLanguage() : "UNKNOWN";
            if (!"UNKNOWN".equals(visitor)) {
                states.computeIfAbsent(ctx.sessionId(), s -> new TranslationSessionState()).lock(visitor);
            }
            ctx.sendText(OutboundMessage.builder().type("tts_end").sessionId(ctx.sessionId()).turnId(turnId).build());
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
    public void onStopTts(SessionContext ctx, InboundMessage msg) {
        // 流式 turn → cancel；否则释放累积缓冲
        TurnSession turn = fixedTurns.remove(msg.turnId());
        if (turn == null) {
            turn = autoTurns.remove(msg.turnId());
        }
        if (turn != null) {
            turn.cancel();
            return;
        }
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

    /** 单个 TTS 音频块按 ≤8KB 分片下发（接口规范 §9 单帧上限）。 */
    private void sendTtsChunk(SessionContext ctx, String turnId, byte[] chunk) {
        for (int off = 0; off < chunk.length; off += CHUNK_SIZE) {
            int len = Math.min(CHUNK_SIZE, chunk.length - off);
            ctx.sendTtsAudio(new TtsAudioFrame(turnId, Arrays.copyOfRange(chunk, off, off + len)));
        }
    }
}
