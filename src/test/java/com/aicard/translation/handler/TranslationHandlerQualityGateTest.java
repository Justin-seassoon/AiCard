package com.aicard.translation.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.ingestion.service.IngestionService;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.api.TurnSession;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.service.SkbService;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import com.aicard.translation.orchestrate.TranslationResult;
import com.aicard.translation.qualify.QualifyTermService;
import com.aicard.translation.qualify.UtteranceQualifier;
import com.aicard.translation.state.TranslationSessionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 质量门控 + 语言漂移专项测试：验证品牌词/白名单/数字/短句不污染 visitor_language 记忆，
 * 以及换游客、夹杂语言时记忆的行为（含已知局限，为联调防漂移评估做基线）。
 */
class TranslationHandlerQualityGateTest {

    private final IngestionService ingestion = mock(IngestionService.class);
    private final SkbService skbService = mock(SkbService.class);
    private final LLMProvider llm = mock(LLMProvider.class);

    private static InboundMessage eou(String turnId, String sourceSide) {
        return InboundMessage.builder().type("eou").sessionId("s1").turnId(turnId)
                .sourceSide(sourceSide).build();
    }

    private static SessionContext ctx(CopyOnWriteArrayList<OutboundMessage> text,
                                      CopyOnWriteArrayList<TtsAudioFrame> binary) {
        return new SessionContext("s1", "dev-1", 1L, 2L, "translate", "auto", "ja-JP", text::add, binary::add);
    }

    /** 构造 handler，词表由 QualifyTermService mock 返回（模拟后台配置的词表）。 */
    private TranslationHandlerImpl handler(TranslationOrchestrator orchestrator, SpeechProvider speech,
                                           Set<String> whitelist, Set<String> brands) {
        QualifyTermService terms = mock(QualifyTermService.class);
        when(terms.getWhitelist(anyLong(), anyLong())).thenReturn(whitelist);
        when(terms.getBrandTerms(anyLong(), anyLong())).thenReturn(brands);
        return new TranslationHandlerImpl(orchestrator, speech, ingestion, skbService, llm,
                new UtteranceQualifier(200, true), terms);
    }

    /** 游客说一句话：startTurnAuto 返回 turn，finish 返回指定 finalText + 检测语言。 */
    private void speakCounterparty(TranslationHandlerImpl handler, SessionContext ctx, String turnId,
                                   SpeechProvider speech, String finalText, String detectedLang) {
        TurnSession turn = mock(TurnSession.class);
        when(speech.startTurnAuto(anyList(), eq("ja-JP"), any())).thenReturn(turn);
        when(turn.finish()).thenReturn(new SpeechTranslationResult(finalText, finalText, detectedLang));
        handler.onAudioChunk(ctx, new AudioChunkFrame(turnId, 0, 1L, (byte) 1, (byte) 1, new byte[]{1}));
        handler.onEndOfUtterance(ctx, eou(turnId, "counterparty"));
    }

    /** 员工说一句话：走累积路径，orchestrator 被调用，捕获传入的 state。 */
    private void speakWearer(TranslationHandlerImpl handler, SessionContext ctx, String turnId,
                             AtomicReference<TranslationSessionState> capturedState,
                             TranslationOrchestrator orchestrator) {
        when(orchestrator.translateUtterance(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    capturedState.set(inv.getArgument(5));
                    return new TranslationResult("translate", null, "UNKNOWN");
                });
        handler.onAudioChunk(ctx, new AudioChunkFrame(turnId, 0, 1L, (byte) 0, (byte) 1, new byte[]{2}));
        handler.onEndOfUtterance(ctx, eou(turnId, "wearer"));
    }

    @Test
    void brandUtteranceDoesNotLockVisitorLanguage() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TranslationHandlerImpl h = handler(orchestrator, speech, Set.of(), Set.of("iphone"));
        CopyOnWriteArrayList<OutboundMessage> text = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binary = new CopyOnWriteArrayList<>();
        SessionContext ctx = ctx(text, binary);

        speakCounterparty(h, ctx, "t1", speech, "iPhone 15", "zh-CN");
        AtomicReference<TranslationSessionState> state = new AtomicReference<>();
        speakWearer(h, ctx, "t2", state, orchestrator);

        assertThat(state.get().visitorLanguage()).isEqualTo("UNKNOWN"); // 品牌词不锁记忆
    }

    @Test
    void whitelistUtteranceDoesNotLockVisitorLanguage() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TranslationHandlerImpl h = handler(orchestrator, speech, Set.of("ok", "ありがとう"), Set.of());
        CopyOnWriteArrayList<OutboundMessage> text = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binary = new CopyOnWriteArrayList<>();
        SessionContext ctx = ctx(text, binary);

        speakCounterparty(h, ctx, "t1", speech, "OK", "en-US");
        AtomicReference<TranslationSessionState> state = new AtomicReference<>();
        speakWearer(h, ctx, "t2", state, orchestrator);

        assertThat(state.get().visitorLanguage()).isEqualTo("UNKNOWN"); // 白名单短句不锁记忆
    }

    @Test
    void numericUtteranceDoesNotLockVisitorLanguage() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TranslationHandlerImpl h = handler(orchestrator, speech, Set.of(), Set.of());
        CopyOnWriteArrayList<OutboundMessage> text = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binary = new CopyOnWriteArrayList<>();
        SessionContext ctx = ctx(text, binary);

        speakCounterparty(h, ctx, "t1", speech, "12345", "zh-CN");
        AtomicReference<TranslationSessionState> state = new AtomicReference<>();
        speakWearer(h, ctx, "t2", state, orchestrator);

        assertThat(state.get().visitorLanguage()).isEqualTo("UNKNOWN"); // 纯数字不锁记忆
    }

    @Test
    void normalUtteranceLocksVisitorLanguage() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TranslationHandlerImpl h = handler(orchestrator, speech, Set.of("ok"), Set.of("iphone"));
        CopyOnWriteArrayList<OutboundMessage> text = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binary = new CopyOnWriteArrayList<>();
        SessionContext ctx = ctx(text, binary);

        speakCounterparty(h, ctx, "t1", speech, "我想买一个包", "zh-CN");
        AtomicReference<TranslationSessionState> state = new AtomicReference<>();
        speakWearer(h, ctx, "t2", state, orchestrator);

        assertThat(state.get().visitorLanguage()).isEqualTo("zh-CN"); // 正常句锁记忆
    }

    @Test
    void visitorSwitchWithBrandKeepsOldMemory() {
        // 已知局限：换游客后，若新游客首句是品牌词，记忆不更新，员工方向仍用旧语言
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TranslationHandlerImpl h = handler(orchestrator, speech, Set.of(), Set.of("iphone"));
        CopyOnWriteArrayList<OutboundMessage> text = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binary = new CopyOnWriteArrayList<>();
        SessionContext ctx = ctx(text, binary);

        speakCounterparty(h, ctx, "t1", speech, "我想买一个包", "zh-CN");
        speakCounterparty(h, ctx, "t2", speech, "iPhone 15", "en-US");

        AtomicReference<TranslationSessionState> state = new AtomicReference<>();
        speakWearer(h, ctx, "t3", state, orchestrator);

        assertThat(state.get().visitorLanguage()).isEqualTo("zh-CN");
    }

    @Test
    void mixedLanguageOverwritesMemory() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        SpeechProvider speech = mock(SpeechProvider.class);
        TranslationHandlerImpl h = handler(orchestrator, speech, Set.of(), Set.of());
        CopyOnWriteArrayList<OutboundMessage> text = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binary = new CopyOnWriteArrayList<>();
        SessionContext ctx = ctx(text, binary);

        speakCounterparty(h, ctx, "t1", speech, "我想买一个包", "zh-CN");
        speakCounterparty(h, ctx, "t2", speech, "I want this one", "en-US");

        AtomicReference<TranslationSessionState> state = new AtomicReference<>();
        speakWearer(h, ctx, "t3", state, orchestrator);

        assertThat(state.get().visitorLanguage()).isEqualTo("en-US");
    }
}
