package com.aicard.translation.orchestrate;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.translation.decision.DecisionInput;
import com.aicard.translation.decision.DirectionDecision;
import com.aicard.translation.decision.LidDecisionEngine;
import com.aicard.translation.state.TranslationSessionState;

import java.util.List;
import java.util.function.Consumer;

/**
 * 翻译编排：fixed 模式跳过 LID 直接按 lang_pair 翻译；AUTO 模式 LID 独立前置 → 决策 → 一步流式翻译。
 * 见架构 spec §7.4 / §7.7。
 */
public class TranslationOrchestrator {

    private final SpeechProvider provider;
    private final LidDecisionEngine decision;

    public TranslationOrchestrator(SpeechProvider provider, LidDecisionEngine decision) {
        this.provider = provider;
        this.decision = decision;
    }

    public TranslationResult translateUtterance(byte[] audio, String sourceSide, String translationMode,
                                                String langPair, boolean whitelistHit,
                                                TranslationSessionState state, List<String> candidates,
                                                String staffLanguage, Consumer<byte[]> onTtsAudioChunk) {
        if ("fixed".equals(translationMode)) {
            return translateFixed(audio, sourceSide, langPair, state, onTtsAudioChunk);
        }
        return translateAuto(audio, sourceSide, whitelistHit, state, candidates, staffLanguage, onTtsAudioChunk);
    }

    /** fixed 模式：跳过 LID 与软锁定，直接按 lang_pair 双向翻译（spec §7.7）。 */
    private TranslationResult translateFixed(byte[] audio, String sourceSide, String langPair,
                                             TranslationSessionState state, Consumer<byte[]> onTtsAudioChunk) {
        String[] pair = langPair.split("-");
        if (pair.length < 2) {
            return new TranslationResult("repeat", "E_PROVIDER", state.visitorLanguage());
        }
        // 游客侧-员工侧：counterparty(游客)→游客侧译员工侧；wearer(员工)→反向
        String src = "wearer".equals(sourceSide) ? pair[1] : pair[0];
        String tgt = "wearer".equals(sourceSide) ? pair[0] : pair[1];
        try {
            provider.translateToSpeech(audio, src, tgt, onTtsAudioChunk);
            return new TranslationResult("translate", null, state.visitorLanguage());
        } catch (ProviderException e) {
            return new TranslationResult("repeat", "E_PROVIDER", state.visitorLanguage());
        }
    }

    /** AUTO 模式：LID 独立前置 → 软锁定决策 → 一步流式翻译（spec §7.4）。 */
    private TranslationResult translateAuto(byte[] audio, String sourceSide, boolean whitelistHit,
                                            TranslationSessionState state, List<String> candidates,
                                            String staffLanguage, Consumer<byte[]> onTtsAudioChunk) {
        LidResult lid;
        try {
            lid = provider.detectLanguage(audio, candidates);
        } catch (ProviderException e) {
            return new TranslationResult("repeat", "E_LID_LOW", state.visitorLanguage());
        }

        DirectionDecision d = decision.decide(new DecisionInput(
                sourceSide, lid.lidLang(), lid.lidConfidence(), whitelistHit, staffLanguage,
                state.visitorLanguage(), state.pending(), 0.8, 0.95));

        switch (d.action()) {
            case "repeat" -> {
                return new TranslationResult("repeat", "E_LID_LOW", state.visitorLanguage());
            }
            case "conflict" -> {
                return new TranslationResult("conflict", "E_CONFLICT", state.visitorLanguage());
            }
            case "no_translate" -> {
                return new TranslationResult("no_translate", "E_SAME_LANG", state.visitorLanguage());
            }
            case "translate" -> {
                // 应用决策出的新记忆（visitor_language + pending），不能用 lock（会清 pending）
                state.apply(d.newVisitorLanguage(), d.newPending());
                try {
                    provider.translateToSpeech(audio, d.srcLang(), d.tgtLang(), onTtsAudioChunk);
                    return new TranslationResult("translate", null, d.newVisitorLanguage());
                } catch (ProviderException e) {
                    // 供应商全挂（Router 已降级仍失败）→ 服务暂不可用
                    return new TranslationResult("repeat", "E_PROVIDER", d.newVisitorLanguage());
                }
            }
            default -> {
                return new TranslationResult("repeat", "E_LID_LOW", state.visitorLanguage());
            }
        }
    }
}
