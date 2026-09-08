package com.aicard.provider.routing;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.api.TurnSession;
import com.aicard.provider.metrics.ProviderMetrics;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 供应商路由：持主/备供应商，能力各自「主成功→记录返回；主 ProviderException→切备选；
 * 备选也失败→记 failure 并抛 ProviderException」。流式 turn 不降级（turn 中途换供应商不可行）。
 */
public class ProviderRouter implements SpeechProvider {

    private final SpeechProvider primary;
    private final SpeechProvider fallback;
    private final ProviderMetrics metrics;

    public ProviderRouter(SpeechProvider primary, SpeechProvider fallback, ProviderMetrics metrics) {
        this.primary = primary;
        this.fallback = fallback;
        this.metrics = metrics;
    }

    @Override
    public String name() { return "router"; }

    @Override
    public LidResult detectLanguage(byte[] audio, List<String> candidates) {
        return call("lid",
                () -> primary.detectLanguage(audio, candidates),
                () -> fallback.detectLanguage(audio, candidates));
    }

    @Override
    public SpeechTranslationResult translateToSpeech(byte[] audio, String src, String tgt, Consumer<byte[]> onAudioChunk) {
        try {
            SpeechTranslationResult r = primary.translateToSpeech(audio, src, tgt, onAudioChunk);
            metrics.recordSuccess(primary.name(), "mt");
            return r;
        } catch (ProviderException e) {
            metrics.recordFailure(primary.name(), "mt");
            try {
                SpeechTranslationResult r = fallback.translateToSpeech(audio, src, tgt, onAudioChunk);
                metrics.recordSuccess(fallback.name(), "mt");
                return r;
            } catch (ProviderException e2) {
                metrics.recordFailure(fallback.name(), "mt");
                throw e2;
            }
        }
    }

    @Override
    public String transcribe(byte[] audio, String language) {
        return call("asr",
                () -> primary.transcribe(audio, language),
                () -> fallback.transcribe(audio, language));
    }

    @Override
    public byte[] synthesize(String text, String language) {
        return call("tts",
                () -> primary.synthesize(text, language),
                () -> fallback.synthesize(text, language));
    }

    @Override
    public TurnSession startTurn(String src, String tgt, Consumer<byte[]> onTtsAudioChunk) {
        return primary.startTurn(src, tgt, onTtsAudioChunk);
    }

    @Override
    public TurnSession startTurnAuto(List<String> candidates, String tgt, Consumer<byte[]> onTtsAudioChunk) {
        return primary.startTurnAuto(candidates, tgt, onTtsAudioChunk);
    }

    private <T> T call(String capability, Supplier<T> primaryCall, Supplier<T> fallbackCall) {
        try {
            T r = primaryCall.get();
            metrics.recordSuccess(primary.name(), capability);
            return r;
        } catch (ProviderException e) {
            metrics.recordFailure(primary.name(), capability);
        }
        try {
            T r = fallbackCall.get();
            metrics.recordSuccess(fallback.name(), capability);
            return r;
        } catch (ProviderException e) {
            metrics.recordFailure(fallback.name(), capability);
            throw e;
        }
    }
}
