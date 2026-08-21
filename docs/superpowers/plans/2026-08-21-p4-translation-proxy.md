# P4 翻译主链路（translation-proxy）· 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现翻译主链路的业务核心：LID 软锁定状态机（说话方判定 + 方向决策 + 降级）与「说话方判定 → LID 独立前置 → ASR → MT → TTS」编排，并以 `TranslationHandler` 实现接入 P3 网关，含 TTS 打断的云端对应逻辑。

**Architecture:** 纯逻辑决策引擎 `LidDecisionEngine`（无副作用，输入状态 + LID 结果 → 输出方向/动作/新记忆），复用 P2 的 `SpeechProvider`（LID/ASR/MT/TTS 四能力），`TranslationHandlerImpl` 实现 P3 的 `TranslationHandler` 接口：`onAudioChunk` 按 turn_id 累积音频、`onEndOfUtterance` 触发编排并回发 `tts_audio`/`error`、`onStopTts` 停旧 TTS。真实供应商后置，本 plan 用 Mock。

**Tech Stack:** Java 17、Spring（`@Component`）、JUnit + Mockito（纯逻辑不依赖 DB）。

**Spec:** `docs/superpowers/specs/2026-08-21-ai-card-software-architecture-design.md`（§7 LID 软锁定状态机、§3.1 翻译主链路、§6.6 TTS 打断云端逻辑、§10 降级）。

## Global Constraints

- 翻译方向**由 `source_side` 决定**（wearer→反向、counterparty→LID 判方向），LID 只决定 visitor_language 记忆与降级（spec §7.2）。
- LID 是**独立前置**调用（spec §7.1）；wearer 发言也跑 LID 仅用于后验串音校验（spec §7.4 ①）。
- visitor_language 会话记忆只由 counterparty 发言更新，wearer 不更新。
- 低置信且非白名单 → **不外放高风险译文**，回 `E_LID_LOW`（spec §10.3，不确定不猜测）。
- `turn_id` 贯穿：上行音频、下行 tts_audio、stop_tts 同 turn（spec §6.5）。

## File Structure（本 plan 创建）

```
src/main/java/com/aicard/translation/
  state/TranslationSessionState.java
  state/PendingSwitch.java
  decision/DirectionDecision.java
  decision/DecisionInput.java
  decision/LidDecisionEngine.java
  orchestrate/TranslationOrchestrator.java
  handler/TranslationHandlerImpl.java
src/test/java/com/aicard/translation/
  state/TranslationSessionStateTest.java
  decision/LidDecisionEngineTest.java
  orchestrate/TranslationOrchestratorTest.java
  handler/TranslationHandlerImplTest.java
```

---

### Task 1: 软锁定会话状态（TranslationSessionState + PendingSwitch）

**Files:**
- Create: `state/PendingSwitch.java`, `state/TranslationSessionState.java`
- Create: `src/test/java/com/aicard/translation/state/TranslationSessionStateTest.java`

**Interfaces:**
- 产出：`TranslationSessionState`（`visitorLanguage`：UNKNOWN 或具体语种；`pending`：待切换计数）+ `PendingSwitch(lang, count)`。

- [ ] **Step 1: 写失败测试**

```java
package com.aicard.translation.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationSessionStateTest {

    @Test
    void startsUnknown() {
        TranslationSessionState s = new TranslationSessionState();
        assertThat(s.visitorLanguage()).isEqualTo("UNKNOWN");
        assertThat(s.pending()).isNull();
    }

    @Test
    void locksAndResets() {
        TranslationSessionState s = new TranslationSessionState();
        s.lock("en-US");
        assertThat(s.visitorLanguage()).isEqualTo("en-US");
        s.reset();
        assertThat(s.visitorLanguage()).isEqualTo("UNKNOWN");
    }

    @Test
    void accumulatesPending() {
        TranslationSessionState s = new TranslationSessionState();
        s.lock("ja-JP");
        s.accumulatePending("en-US");
        assertThat(s.pending()).isEqualTo(new PendingSwitch("en-US", 1));
        s.accumulatePending("en-US");
        assertThat(s.pending()).isEqualTo(new PendingSwitch("en-US", 2));
    }

    @Test
    void resetsPendingWhenDifferentLang() {
        TranslationSessionState s = new TranslationSessionState();
        s.lock("ja-JP");
        s.accumulatePending("en-US");
        s.accumulatePending("ko-KR");
        assertThat(s.pending()).isEqualTo(new PendingSwitch("ko-KR", 1));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn test -Dtest=TranslationSessionStateTest
```

Expected: FAIL。

- [ ] **Step 3: 实现**

```java
package com.aicard.translation.state;

public record PendingSwitch(String lang, int count) {
    public PendingSwitch bump() { return new PendingSwitch(lang, count + 1); }
}
```

```java
package com.aicard.translation.state;

public class TranslationSessionState {

    private volatile String visitorLanguage = "UNKNOWN";
    private volatile PendingSwitch pending;

    public String visitorLanguage() { return visitorLanguage; }
    public PendingSwitch pending() { return pending; }

    public void lock(String lang) {
        this.visitorLanguage = lang;
        this.pending = null;
    }

    public void reset() {
        this.visitorLanguage = "UNKNOWN";
        this.pending = null;
    }

    public PendingSwitch accumulatePending(String lang) {
        if (pending != null && pending.lang().equals(lang)) {
            pending = pending.bump();
        } else {
            pending = new PendingSwitch(lang, 1);
        }
        return pending;
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=TranslationSessionStateTest
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add translation session state (visitor_language soft-lock + pending)"
```

---

### Task 2: LID 决策引擎（§7.4 决策流程）

**Files:**
- Create: `decision/DirectionDecision.java`, `decision/DecisionInput.java`, `decision/LidDecisionEngine.java`
- Create: `src/test/java/com/aicard/translation/decision/LidDecisionEngineTest.java`

**Interfaces:**
- Consumes: `TranslationSessionState`、`PendingSwitch`（Task 1）。
- Produces: `LidDecisionEngine.decide(DecisionInput) → DirectionDecision`（`srcLang/tgtLang/action/newVisitorLanguage/newPending`；`action ∈ translate|repeat|conflict|no_translate`）。

- [ ] **Step 1: 写失败测试（覆盖 spec §7.4 关键分支）**

```java
package com.aicard.translation.decision;

import com.aicard.translation.state.PendingSwitch;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LidDecisionEngineTest {

    private final LidDecisionEngine engine = new LidDecisionEngine();

    private DecisionInput in(String source, String lidLang, double conf, String visitor) {
        return new DecisionInput(source, lidLang, conf, false, "ja-JP", visitor, null, 0.8, 0.95);
    }

    @Test
    void wearerWithLockedVisitorTranslatesBackward() {
        DirectionDecision d = engine.decide(in("wearer", "ja-JP", 0.9, "zh-CN"));
        assertThat(d.action()).isEqualTo("translate");
        assertThat(d.srcLang()).isEqualTo("ja-JP");
        assertThat(d.tgtLang()).isEqualTo("zh-CN");
    }

    @Test
    void wearerWithUnknownVisitorRepeats() {
        DirectionDecision d = engine.decide(in("wearer", "ja-JP", 0.9, "UNKNOWN"));
        assertThat(d.action()).isEqualTo("repeat");
    }

    @Test
    void wearerWithConflictLidConflicts() {
        DirectionDecision d = engine.decide(in("wearer", "en-US", 0.97, "zh-CN"));
        assertThat(d.action()).isEqualTo("conflict");
    }

    @Test
    void counterpartyFirstValidLocks() {
        DirectionDecision d = engine.decide(in("counterparty", "zh-CN", 0.9, "UNKNOWN"));
        assertThat(d.action()).isEqualTo("translate");
        assertThat(d.srcLang()).isEqualTo("zh-CN");
        assertThat(d.tgtLang()).isEqualTo("ja-JP");
        assertThat(d.newVisitorLanguage()).isEqualTo("zh-CN");
    }

    @Test
    void counterpartyLowConfidenceRepeats() {
        DirectionDecision d = engine.decide(in("counterparty", "zh-CN", 0.5, "UNKNOWN"));
        assertThat(d.action()).isEqualTo("repeat");
    }

    @Test
    void counterpartySameAsStaffNoTranslate() {
        DirectionDecision d = engine.decide(in("counterparty", "ja-JP", 0.9, "zh-CN"));
        assertThat(d.action()).isEqualTo("no_translate");
    }

    @Test
    void lockedHoldsUntilTwoConsecutiveNewLang() {
        DecisionInput first = in("counterparty", "en-US", 0.9, "ja-JP");
        DirectionDecision d1 = engine.decide(first);
        assertThat(d1.newVisitorLanguage()).isEqualTo("ja-JP"); // 第一句不切换
        assertThat(d1.newPending()).isEqualTo(new PendingSwitch("en-US", 1));

        DirectionDecision d2 = engine.decide(new DecisionInput(
                "counterparty", "en-US", 0.9, false, "ja-JP", "ja-JP",
                new PendingSwitch("en-US", 1), 0.8, 0.95));
        assertThat(d2.newVisitorLanguage()).isEqualTo("en-US"); // 连续两句切换
    }

    @Test
    void superHighConfidenceSwitchesImmediately() {
        DirectionDecision d = engine.decide(in("counterparty", "en-US", 0.98, "ja-JP"));
        assertThat(d.newVisitorLanguage()).isEqualTo("en-US");
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn test -Dtest=LidDecisionEngineTest
```

Expected: FAIL。

- [ ] **Step 3: 实现 DirectionDecision + DecisionInput**

```java
package com.aicard.translation.decision;

import com.aicard.translation.state.PendingSwitch;

public record DirectionDecision(
        String srcLang, String tgtLang, String action,
        String newVisitorLanguage, PendingSwitch newPending) {

    public static DirectionDecision repeat() {
        return new DirectionDecision(null, null, "repeat", null, null);
    }
    public static DirectionDecision conflict() {
        return new DirectionDecision(null, null, "conflict", null, null);
    }
    public static DirectionDecision noTranslate() {
        return new DirectionDecision(null, null, "no_translate", null, null);
    }
}
```

```java
package com.aicard.translation.decision;

import com.aicard.translation.state.PendingSwitch;

public record DecisionInput(
        String sourceSide,     // wearer | counterparty | uncertain
        String lidLang,        // LID 结果（可为 null 表低置信）
        double lidConfidence,
        boolean whitelistHit,
        String staffLanguage,
        String visitorLanguage,
        PendingSwitch pending,
        double theta,
        double thetaSuper
) {}
```

- [ ] **Step 4: 实现 LidDecisionEngine**

```java
package com.aicard.translation.decision;

import com.aicard.translation.state.PendingSwitch;

public class LidDecisionEngine {

    public DirectionDecision decide(DecisionInput in) {
        if ("uncertain".equals(in.sourceSide())) {
            return DirectionDecision.conflict();
        }

        if ("wearer".equals(in.sourceSide())) {
            return decideWearer(in);
        }
        return decideCounterparty(in);
    }

    private DirectionDecision decideWearer(DecisionInput in) {
        if ("UNKNOWN".equals(in.visitorLanguage())) {
            return DirectionDecision.repeat(); // 无目标语言，提示「请先让游客说话」
        }
        // 后验串音校验：wearer 来源但 LID 明显非 staff 语言
        if (in.lidLang() != null && !in.lidLang().equals(in.staffLanguage())
                && in.lidConfidence() >= in.thetaSuper()) {
            return DirectionDecision.conflict();
        }
        return new DirectionDecision(in.staffLanguage(), in.visitorLanguage(), "translate",
                in.visitorLanguage(), in.pending());
    }

    private DirectionDecision decideCounterparty(DecisionInput in) {
        String lidLang = in.lidLang();
        double conf = in.lidConfidence();

        // 同语种：游客说员工语言
        if (lidLang != null && lidLang.equals(in.staffLanguage())) {
            return DirectionDecision.noTranslate();
        }

        if ("UNKNOWN".equals(in.visitorLanguage())) {
            if (lidLang != null && conf >= in.theta()) {
                return new DirectionDecision(lidLang, in.staffLanguage(), "translate",
                        lidLang, in.pending());
            }
            return DirectionDecision.repeat(); // 低置信或无法建立，不外放
        }

        String locked = in.visitorLanguage(); // LOCKED(L)

        // 低置信：白名单直通（LOCKED），否则不外放
        if (lidLang == null || conf < in.theta()) {
            if (in.whitelistHit()) {
                return new DirectionDecision(locked, in.staffLanguage(), "translate",
                        locked, in.pending());
            }
            return DirectionDecision.repeat();
        }

        // 高置信
        if (lidLang.equals(locked)) {
            return new DirectionDecision(locked, in.staffLanguage(), "translate",
                    locked, in.pending());
        }

        // 高置信新语言 X≠L：本句按 X 翻译，pending 累加，连续两次或超高置信才切换记忆
        PendingSwitch newPending = in.pending() == null
                ? new PendingSwitch(lidLang, 1)
                : (in.pending().lang().equals(lidLang) ? in.pending().bump() : new PendingSwitch(lidLang, 1));

        boolean switchNow = newPending.count() >= 2 || conf >= in.thetaSuper();
        String newVisitor = switchNow ? lidLang : locked;
        return new DirectionDecision(lidLang, in.staffLanguage(), "translate", newVisitor, newPending);
    }
}
```

- [ ] **Step 5: 运行确认通过**

```bash
mvn test -Dtest=LidDecisionEngineTest
```

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add LID soft-lock decision engine (speaker + direction + degradation)"
```

---

### Task 3: 翻译编排（LID 独立前置 → ASR → MT → TTS）

**Files:**
- Create: `orchestrate/TranslationOrchestrator.java`
- Create: `src/test/java/com/aicard/translation/orchestrate/TranslationOrchestratorTest.java`

**Interfaces:**
- Consumes: `SpeechProvider`（P2）、`LidDecisionEngine`/`TranslationSessionState`（Task 1/2）。
- Produces: `TranslationOrchestrator.translateUtterance(audio, sourceSide, whitelistHit, state, candidates) → TranslationResult`，其中 `TranslationResult(action, ttsAudio, errorCode, newVisitorLanguage)`。

- [ ] **Step 1: 写失败测试（Mock SpeechProvider）**

```java
package com.aicard.translation.orchestrate;

import com.aicard.provider.api.*;
import com.aicard.provider.mock.MockSpeechProvider;
import com.aicard.translation.decision.LidDecisionEngine;
import com.aicard.translation.state.TranslationSessionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationOrchestratorTest {

    private final MockSpeechProvider provider = new MockSpeechProvider();
    private final TranslationOrchestrator orchestrator =
            new TranslationOrchestrator(provider, new LidDecisionEngine());

    @Test
    void translatesCounterpartyUtteranceEndToEnd() {
        provider.setLidResult(new LidResult("zh-CN", 0.9));
        provider.setTranslatedText("你好");
        provider.setTtsAudio(new byte[]{7, 8});

        TranslationResult r = orchestrator.translateUtterance(
                new byte[]{1}, "counterparty", false, new TranslationSessionState(),
                List.of("zh-CN", "en-US", "ko-KR"), "ja-JP");

        assertThat(r.action()).isEqualTo("translate");
        assertThat(r.ttsAudio()).containsExactly(7, 8);
        assertThat(r.newVisitorLanguage()).isEqualTo("zh-CN");
    }

    @Test
    void lowConfidenceReturnsRepeatWithoutTts() {
        provider.setLidResult(new LidResult("zh-CN", 0.3));

        TranslationResult r = orchestrator.translateUtterance(
                new byte[]{1}, "counterparty", false, new TranslationSessionState(),
                List.of("zh-CN"), "ja-JP");

        assertThat(r.action()).isEqualTo("repeat");
        assertThat(r.errorCode()).isEqualTo("E_LID_LOW");
        assertThat(r.ttsAudio()).isNull();
    }

    @Test
    void whitelistHitInLockedStatePassthrough() {
        provider.setLidResult(new LidResult("en-US", 0.3));
        provider.setTranslatedText("OK");
        provider.setTtsAudio(new byte[]{9});
        TranslationSessionState state = new TranslationSessionState();
        state.lock("en-US");

        TranslationResult r = orchestrator.translateUtterance(
                new byte[]{1}, "counterparty", true, state, List.of("en-US"), "ja-JP");

        assertThat(r.action()).isEqualTo("translate");
        assertThat(r.ttsAudio()).containsExactly(9);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn test -Dtest=TranslationOrchestratorTest
```

Expected: FAIL（`TranslationOrchestrator`/`TranslationResult` 不存在）。

- [ ] **Step 3: 实现 TranslationResult + TranslationOrchestrator**

`TranslationResult.java`（同包）：

```java
package com.aicard.translation.orchestrate;

public record TranslationResult(
        String action,           // translate | repeat | conflict | no_translate
        byte[] ttsAudio,
        String errorCode,
        String newVisitorLanguage
) {}
```

`TranslationOrchestrator.java`：

```java
package com.aicard.translation.orchestrate;

import com.aicard.provider.api.*;
import com.aicard.translation.decision.DecisionInput;
import com.aicard.translation.decision.DirectionDecision;
import com.aicard.translation.decision.LidDecisionEngine;
import com.aicard.translation.state.TranslationSessionState;

import java.util.List;

public class TranslationOrchestrator {

    private final SpeechProvider provider;
    private final LidDecisionEngine decision;

    public TranslationOrchestrator(SpeechProvider provider, LidDecisionEngine decision) {
        this.provider = provider;
        this.decision = decision;
    }

    public TranslationResult translateUtterance(byte[] audio, String sourceSide, boolean whitelistHit,
                                                TranslationSessionState state, List<String> candidates,
                                                String staffLanguage) {
        // LID 独立前置（wearer 也跑，用于后验串音校验）
        LidResult lid = null;
        try {
            lid = provider.detectLanguage(audio, candidates);
        } catch (ProviderException e) {
            return new TranslationResult("repeat", null, "E_LID_LOW", state.visitorLanguage());
        }

        DirectionDecision d = decision.decide(new DecisionInput(
                sourceSide, lid.lidLang(), lid.lidConfidence(), whitelistHit, staffLanguage,
                state.visitorLanguage(), state.pending(), 0.8, 0.95));

        switch (d.action()) {
            case "repeat" -> {
                return new TranslationResult("repeat", null, "E_LID_LOW", state.visitorLanguage());
            }
            case "conflict" -> {
                return new TranslationResult("conflict", null, "E_CONFLICT", state.visitorLanguage());
            }
            case "no_translate" -> {
                return new TranslationResult("no_translate", null, null, state.visitorLanguage());
            }
            case "translate" -> {
                if (!"UNKNOWN".equals(d.newVisitorLanguage())) {
                    state.lock(d.newVisitorLanguage());
                }
                TranscribeResult asr = provider.transcribe(audio, d.srcLang());
                TranslateResult mt = provider.translate(asr.finalText(), d.srcLang(), d.tgtLang());
                SynthesizeResult tts = provider.synthesize(mt.translatedText(), d.tgtLang());
                return new TranslationResult("translate", tts.ttsAudio(), null, d.newVisitorLanguage());
            }
            default -> {
                return new TranslationResult("repeat", null, "E_LID_LOW", state.visitorLanguage());
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=TranslationOrchestratorTest
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add translation orchestrator (LID -> ASR -> MT -> TTS)"
```

---

### Task 4: TranslationHandler 实现（音频缓存 + 回发 + stop_tts）

**Files:**
- Create: `handler/TranslationHandlerImpl.java`
- Create: `src/test/java/com/aicard/translation/handler/TranslationHandlerImplTest.java`

**Interfaces:**
- Consumes: `TranslationHandler`（P3）、`SessionContext`、`TranslationOrchestrator`（Task 3）。
- Produces: `TranslationHandlerImpl`：`onAudioChunk` 按 turn_id 累积音频、`onEndOfUtterance` 触发编排并 `SessionContext.send` 回发 `tts_audio`/`error`、`onStopTts` 置位「停旧 TTS」开关（新 turn 到达即停）。

- [ ] **Step 1: 写失败测试（Mock orchestrator + 真实 SessionContext）**

```java
package com.aicard.translation.handler;

import com.aicard.gateway.protocol.AudioChunkFrame;
import com.aicard.gateway.protocol.InboundMessage;
import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.protocol.TtsAudioFrame;
import com.aicard.gateway.session.SessionContext;
import com.aicard.translation.orchestrate.TranslationOrchestrator;
import com.aicard.translation.orchestrate.TranslationResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranslationHandlerImplTest {

    private static InboundMessage eou(String turnId) {
        return new InboundMessage("eou", "s1", turnId, "counterparty", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void accumulatesAudioAndSendsTtsOnEou() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        when(orchestrator.translateUtterance(any(), any(), any(), any(), any(), any()))
                .thenReturn(new TranslationResult("translate", new byte[]{5, 6}, null, "zh-CN"));

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator);
        SessionContext ctx = new SessionContext("s1", "dev-1", 1L, 2L, "translate", "ja-JP",
                textSent::add, binarySent::add);

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 1, (byte) 1, new byte[]{1, 2}));
        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 1, 2L, (byte) 1, (byte) 1, new byte[]{3, 4}));
        handler.onEndOfUtterance(ctx, eou("t1"));

        assertThat(binarySent).anyMatch(f -> f.turnId().equals("t1") && f.audio().length == 4);
        assertThat(textSent).anyMatch(m -> "tts_end".equals(m.type()));
        assertThat(textSent).anyMatch(m -> "language_state".equals(m.type()) && "zh-CN".equals(m.visitorLanguage()));
    }

    @Test
    void lowConfidenceSendsError() {
        TranslationOrchestrator orchestrator = mock(TranslationOrchestrator.class);
        when(orchestrator.translateUtterance(any(), any(), any(), any(), any(), any()))
                .thenReturn(new TranslationResult("repeat", null, "E_LID_LOW", "UNKNOWN"));

        CopyOnWriteArrayList<OutboundMessage> textSent = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<TtsAudioFrame> binarySent = new CopyOnWriteArrayList<>();
        TranslationHandlerImpl handler = new TranslationHandlerImpl(orchestrator);
        SessionContext ctx = new SessionContext("s1", "dev-1", 1L, 2L, "translate", "ja-JP",
                textSent::add, binarySent::add);

        handler.onAudioChunk(ctx, new AudioChunkFrame("t1", 0, 1L, (byte) 1, (byte) 1, new byte[]{1, 2}));
        handler.onEndOfUtterance(ctx, eou("t1"));

        assertThat(textSent).anyMatch(m -> "error".equals(m.type()) && "E_LID_LOW".equals(m.errorCode()));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn test -Dtest=TranslationHandlerImplTest
```

Expected: FAIL。

- [ ] **Step 3: 实现 TranslationHandlerImpl**

```java
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
        ByteArrayOutputStream b = buffers.remove(turnId);
        byte[] audio = b == null ? new byte[0] : b.toByteArray();

        TranslationSessionState state = states.computeIfAbsent(ctx.sessionId(), s -> new TranslationSessionState());
        TranslationResult r = orchestrator.translateUtterance(
                audio, msg.sourceSide(), false, state, DEFAULT_CANDIDATES, ctx.staffLanguage());

        if ("translate".equals(r.action())) {
            ctx.sendTtsAudio(new TtsAudioFrame(turnId, r.ttsAudio()));
            ctx.sendText(new OutboundMessage("tts_end", ctx.sessionId(), turnId, null, null,
                    null, null, null, null, null, null, null));
            String stateStr = "UNKNOWN".equals(r.newVisitorLanguage()) ? "unknown" : "locked";
            ctx.sendText(new OutboundMessage("language_state", ctx.sessionId(), turnId, null, null,
                    null, null, null, null, r.newVisitorLanguage(), null, stateStr));
        } else {
            ctx.sendText(new OutboundMessage("error", ctx.sessionId(), turnId, null, null,
                    r.errorCode(), "please repeat", "ask_repeat", null, null, null, null));
        }
    }

    @Override
    public void onStopTts(SessionContext ctx, InboundMessage msg) {
        // 释放本轮上下文，保留会话与 visitor_language 记忆（spec §6.6）——不 reset state
        buffers.remove(msg.turnId());
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=TranslationHandlerImplTest
```

Expected: PASS。

- [ ] **Step 5: 全量测试 + Commit**

```bash
mvn test
git add -A
git commit -m "feat: implement translation handler (audio buffer, tts reply, stop_tts)"
```

---

## Self-Review

- **Spec 覆盖**：落地 spec §7.2（source_side 决定方向）、§7.1（LID 独立前置）、§7.4（同语种/UNKNOWN/LOCKED/低置信/白名单/连续两次/超高置信全分支）、§7.5（pending 累积）、§10.3（E_LID_LOW 不外放）、§6.6（stop_tts 保留会话不清 visitor_language）、§3.1（双向翻译）。
- **占位符扫描**：无 TBD/TODO；所有 step 含实际代码。
- **类型一致性**：`DirectionDecision.newPending`/`newVisitorLanguage` 与 `TranslationSessionState` 字段一致；`TranslationHandlerImpl` 实现 `TranslationHandler` 接口方法签名与 P3 定义一致；`TranslationOrchestratorTest` 用 `MockSpeechProvider` 控制 LID/MT/TTS 返回。

### 已知边界（联调前处理，非本 plan 范围）

- 质量门控（min_duration 最短语音时长、纯数字、品牌名词表）在 orchestrator 层尚未实现——spec §7.4 ②，需联调时与 VAD 尾包参数一并冻结，本 plan 的 `whitelistHit` 由调用方传入。
- 短词白名单表、θ/θ_super 可配置化（spec §7.6）——当前硬编码 0.8/0.95，联调时迁到配置下发。
- 真实供应商的流式 TTS 首帧回发、供应商超时/重试预算（spec 附录 B）——真实适配器（联调前 plan）落地。
- `DEFAULT_CANDIDATES` 硬编码四语种——联调时从设备/门店配置读取（spec §11 候选语种）。
