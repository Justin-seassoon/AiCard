# P2 供应商能力抽象层（provider-abstraction）· 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立供应商能力抽象层：定义 LID/ASR/MT/TTS 四能力的统一接口，提供 Mock 适配器（开发测试用）、多供应商路由 + 失败降级、成本计量，使上层（P4 translation-proxy）只依赖抽象、不绑定具体供应商。

**Architecture:** 一个 `SpeechProvider` 组合接口（一个供应商 = LID + ASR + MT + TTS 四能力 + name）。`MockSpeechProvider` 可编程返回；`ProviderRouter` 持主/备供应商，主失败自动切备选；`ProviderMetrics` 记录每次调用的供应商/能力/耗时/成败。真实 Azure/Google 适配器**后置到联调前**（需凭证 + 官方文档校准 + 联调验证，见 spec §5 前置验证项），本 plan 只交付抽象 + Mock + 路由/降级/成本，保证 P4 可立即开发。

**Tech Stack:** Java 17、Spring（`@Component` 装配）、纯 JUnit（不依赖 DB，测试快）。

**Spec:** `docs/superpowers/specs/2026-08-21-ai-card-software-architecture-design.md`（§5 供应商能力抽象）。

## Global Constraints

- 接口定义在 `com.aicard.provider.api`；能力结果用 `record` 数据类。
- 供应商异常统一抛 `ProviderException`（unchecked），路由层据此降级。
- 音频载荷统一 `byte[]`（P2 不涉及编解码，上层传入原始字节）。
- 所有供应商实现必须实现 `SpeechProvider`（组合四能力），新增供应商 = 新增实现类。
- P2 不引入数据库/外部依赖；测试纯 JUnit，不启动 Spring 上下文（用 `new` 直接构造，`Mockito` 用于验证降级路径）。

## File Structure（本 plan 创建）

```
src/main/java/com/aicard/provider/
  api/LidResult.java
  api/TranscribeResult.java
  api/TranslateResult.java
  api/SynthesizeResult.java
  api/ProviderException.java
  api/LIDProvider.java
  api/ASRProvider.java
  api/MTProvider.java
  api/TTSProvider.java
  api/SpeechProvider.java
  mock/MockSpeechProvider.java
  routing/ProviderRouter.java
  metrics/ProviderMetrics.java
src/test/java/com/aicard/provider/
  routing/ProviderRouterTest.java
  metrics/ProviderMetricsTest.java
```

---

### Task 1: 能力接口与结果数据类

**Files:**
- Create: `api/LidResult.java`, `api/TranscribeResult.java`, `api/TranslateResult.java`, `api/SynthesizeResult.java`
- Create: `api/ProviderException.java`
- Create: `api/LIDProvider.java`, `api/ASRProvider.java`, `api/MTProvider.java`, `api/TTSProvider.java`, `api/SpeechProvider.java`

**Interfaces:**
- 产出：四能力接口 + 组合接口 `SpeechProvider`；结果 `record` 数据类；`ProviderException`。

- [ ] **Step 1: 写结果数据类与异常**

```java
package com.aicard.provider.api;

public record LidResult(String lidLang, double lidConfidence) {}
```

```java
package com.aicard.provider.api;

public record TranscribeResult(String finalText) {}
```

```java
package com.aicard.provider.api;

public record TranslateResult(String translatedText) {}
```

```java
package com.aicard.provider.api;

public record SynthesizeResult(byte[] ttsAudio) {}
```

```java
package com.aicard.provider.api;

public class ProviderException extends RuntimeException {
    public ProviderException(String message) { super(message); }
    public ProviderException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 2: 写四能力接口 + 组合接口**

```java
package com.aicard.provider.api;

import java.util.List;

public interface LIDProvider {
    LidResult detectLanguage(byte[] audio, List<String> candidates);
}
```

```java
package com.aicard.provider.api;

public interface ASRProvider {
    TranscribeResult transcribe(byte[] audio, String lang);
}
```

```java
package com.aicard.provider.api;

public interface MTProvider {
    TranslateResult translate(String text, String src, String tgt);
}
```

```java
package com.aicard.provider.api;

public interface TTSProvider {
    SynthesizeResult synthesize(String text, String lang);
}
```

```java
package com.aicard.provider.api;

public interface SpeechProvider extends LIDProvider, ASRProvider, MTProvider, TTSProvider {
    String name();
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn -q compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: define provider abstraction interfaces and result records"
```

---

### Task 2: Mock 适配器

**Files:**
- Create: `src/main/java/com/aicard/provider/mock/MockSpeechProvider.java`
- Create: `src/test/java/com/aicard/provider/mock/MockSpeechProviderTest.java`

**Interfaces:**
- Consumes: `SpeechProvider` 接口（Task 1）。
- Produces: `MockSpeechProvider`，字段可编程（测试/P4 开发时控制四能力返回与失败开关）：
  - `setLidResult(...)`, `setFinalText(...)`, `setTranslatedText(...)`, `setTtsAudio(...)`
  - `setFailAll(boolean)` / `failNext(boolean)`

- [ ] **Step 1: 写失败测试**

`MockSpeechProviderTest.java`：

```java
package com.aicard.provider.mock;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.ProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockSpeechProviderTest {

    @Test
    void returnsConfiguredResults() {
        MockSpeechProvider mock = new MockSpeechProvider();
        mock.setLidResult(new LidResult("en-US", 0.95));
        mock.setFinalText("hello");
        mock.setTranslatedText("こんにちは");
        mock.setTtsAudio(new byte[]{1, 2, 3});

        assertThat(mock.name()).isEqualTo("mock");
        assertThat(mock.detectLanguage(new byte[]{9}, List.of("ja-JP", "en-US")))
                .isEqualTo(new LidResult("en-US", 0.95));
        assertThat(mock.transcribe(new byte[]{9}, "en-US").finalText()).isEqualTo("hello");
        assertThat(mock.translate("hello", "en", "ja").translatedText()).isEqualTo("こんにちは");
        assertThat(mock.synthesize("こんにちは", "ja-JP").ttsAudio()).containsExactly(1, 2, 3);
    }

    @Test
    void failAllThrows() {
        MockSpeechProvider mock = new MockSpeechProvider();
        mock.setFailAll(true);

        assertThatThrownBy(() -> mock.detectLanguage(new byte[]{9}, List.of("ja-JP")))
                .isInstanceOf(ProviderException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn test -Dtest=MockSpeechProviderTest
```

Expected: FAIL（`MockSpeechProvider` 不存在）。

- [ ] **Step 3: 实现 MockSpeechProvider**

```java
package com.aicard.provider.mock;

import com.aicard.provider.api.*;

import java.util.List;

public class MockSpeechProvider implements SpeechProvider {

    private volatile LidResult lidResult = new LidResult("ja-JP", 0.9);
    private volatile String finalText = "こんにちは";
    private volatile String translatedText = "你好";
    private volatile byte[] ttsAudio = new byte[]{1, 2, 3};
    private volatile boolean failAll = false;
    private volatile boolean failNext = false;

    public void setLidResult(LidResult lidResult) { this.lidResult = lidResult; }
    public void setFinalText(String finalText) { this.finalText = finalText; }
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }
    public void setTtsAudio(byte[] ttsAudio) { this.ttsAudio = ttsAudio; }
    public void setFailAll(boolean failAll) { this.failAll = failAll; }
    public void failNext() { this.failNext = true; }

    @Override
    public String name() { return "mock"; }

    @Override
    public LidResult detectLanguage(byte[] audio, List<String> candidates) {
        throwIfFail();
        return lidResult;
    }

    @Override
    public TranscribeResult transcribe(byte[] audio, String lang) {
        throwIfFail();
        return new TranscribeResult(finalText);
    }

    @Override
    public TranslateResult translate(String text, String src, String tgt) {
        throwIfFail();
        return new TranslateResult(translatedText);
    }

    @Override
    public SynthesizeResult synthesize(String text, String lang) {
        throwIfFail();
        return new SynthesizeResult(ttsAudio);
    }

    private void throwIfFail() {
        if (failAll || failNext) {
            failNext = false;
            throw new ProviderException("mock provider failure");
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=MockSpeechProviderTest
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add programmable mock speech provider"
```

---

### Task 3: 供应商路由与失败降级

**Files:**
- Create: `src/main/java/com/aicard/provider/routing/ProviderRouter.java`
- Create: `src/test/java/com/aicard/provider/routing/ProviderRouterTest.java`

**Interfaces:**
- Consumes: `SpeechProvider`、`ProviderMetrics`（Task 4，本 Task 先以构造注入的 `ProviderMetrics` 占位——若 Task 4 未建，先用一个内联简化版，见 Step）。
- Produces: `ProviderRouter` 实现 `SpeechProvider`，持主/备供应商，四能力各自「主成功→记录+返回；主 `ProviderException`→记录失败→切备选；备选也失败→抛 `ProviderException`」。

- [ ] **Step 1: 写降级失败测试（用 Mockito 造主/备）**

`ProviderRouterTest.java`：

```java
package com.aicard.provider.routing;

import com.aicard.provider.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProviderRouterTest {

    private SpeechProvider primary = mock(SpeechProvider.class);
    private SpeechProvider fallback = mock(SpeechProvider.class);

    private ProviderRouter router() {
        return new ProviderRouter(primary, fallback, new ProviderMetrics());
    }

    @Test
    void usesPrimaryWhenHealthy() {
        when(primary.name()).thenReturn("azure");
        when(primary.translate(any(), any(), any())).thenReturn(new TranslateResult("ok"));

        TranslateResult r = router().translate("hi", "en", "ja");

        assertThat(r.translatedText()).isEqualTo("ok");
        verify(primary).translate("hi", "en", "ja");
        verifyNoInteractions(fallback);
    }

    @Test
    void fallsBackWhenPrimaryFails() {
        when(primary.name()).thenReturn("azure");
        when(fallback.name()).thenReturn("google");
        when(primary.translate(any(), any(), any())).thenThrow(new ProviderException("azure down"));
        when(fallback.translate(any(), any(), any())).thenReturn(new TranslateResult("fallback-ok"));

        TranslateResult r = router().translate("hi", "en", "ja");

        assertThat(r.translatedText()).isEqualTo("fallback-ok");
    }

    @Test
    void propagatesWhenBothFail() {
        when(primary.name()).thenReturn("azure");
        when(fallback.name()).thenReturn("google");
        when(primary.translate(any(), any(), any())).thenThrow(new ProviderException("azure down"));
        when(fallback.translate(any(), any(), any())).thenThrow(new ProviderException("google down"));

        assertThatThrownBy(() -> router().translate("hi", "en", "ja"))
                .isInstanceOf(ProviderException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn test -Dtest=ProviderRouterTest
```

Expected: FAIL（`ProviderRouter`/`ProviderMetrics` 不存在）。

- [ ] **Step 3: 实现 ProviderMetrics（先做最小版，Task 4 再补指标查询）**

```java
package com.aicard.provider.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ProviderMetrics {

    public static final class Key {
        public final String provider;
        public final String capability;
        public Key(String provider, String capability) { this.provider = provider; this.capability = capability; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return k.provider.equals(provider) && k.capability.equals(capability);
        }
        @Override public int hashCode() { return 31 * provider.hashCode() + capability.hashCode(); }
    }

    private final Map<Key, AtomicLong> success = new ConcurrentHashMap<>();
    private final Map<Key, AtomicLong> failure = new ConcurrentHashMap<>();

    public void recordSuccess(String provider, String capability) {
        success.computeIfAbsent(new Key(provider, capability), k -> new AtomicLong()).incrementAndGet();
    }

    public void recordFailure(String provider, String capability) {
        failure.computeIfAbsent(new Key(provider, capability), k -> new AtomicLong()).incrementAndGet();
    }

    public long successCount(String provider, String capability) {
        var c = success.get(new Key(provider, capability));
        return c == null ? 0 : c.get();
    }

    public long failureCount(String provider, String capability) {
        var c = failure.get(new Key(provider, capability));
        return c == null ? 0 : c.get();
    }
}
```

- [ ] **Step 4: 实现 ProviderRouter**

```java
package com.aicard.provider.routing;

import com.aicard.provider.api.*;
import com.aicard.provider.metrics.ProviderMetrics;

import java.util.List;

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
        try {
            LidResult r = primary.detectLanguage(audio, candidates);
            metrics.recordSuccess(primary.name(), "lid");
            return r;
        } catch (ProviderException e) {
            metrics.recordFailure(primary.name(), "lid");
            LidResult r = fallback.detectLanguage(audio, candidates);
            metrics.recordSuccess(fallback.name(), "lid");
            return r;
        }
    }

    @Override
    public TranscribeResult transcribe(byte[] audio, String lang) {
        try {
            TranscribeResult r = primary.transcribe(audio, lang);
            metrics.recordSuccess(primary.name(), "asr");
            return r;
        } catch (ProviderException e) {
            metrics.recordFailure(primary.name(), "asr");
            TranscribeResult r = fallback.transcribe(audio, lang);
            metrics.recordSuccess(fallback.name(), "asr");
            return r;
        }
    }

    @Override
    public TranslateResult translate(String text, String src, String tgt) {
        try {
            TranslateResult r = primary.translate(text, src, tgt);
            metrics.recordSuccess(primary.name(), "mt");
            return r;
        } catch (ProviderException e) {
            metrics.recordFailure(primary.name(), "mt");
            TranslateResult r = fallback.translate(text, src, tgt);
            metrics.recordSuccess(fallback.name(), "mt");
            return r;
        }
    }

    @Override
    public SynthesizeResult synthesize(String text, String lang) {
        try {
            SynthesizeResult r = primary.synthesize(text, lang);
            metrics.recordSuccess(primary.name(), "tts");
            return r;
        } catch (ProviderException e) {
            metrics.recordFailure(primary.name(), "tts");
            SynthesizeResult r = fallback.synthesize(text, lang);
            metrics.recordSuccess(fallback.name(), "tts");
            return r;
        }
    }
}
```

- [ ] **Step 5: 运行确认通过**

```bash
mvn test -Dtest=ProviderRouterTest
```

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add provider router with failover and metrics"
```

---

### Task 4: 成本计量指标查询

**Files:**
- Create: `src/test/java/com/aicard/provider/metrics/ProviderMetricsTest.java`

**Interfaces:**
- Consumes: `ProviderMetrics`（Task 3）。
- Produces: 无新生产代码（Task 3 已含 `ProviderMetrics`）；本 Task 用测试锁定其计数语义。

- [ ] **Step 1: 写指标测试**

`ProviderMetricsTest.java`：

```java
package com.aicard.provider.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderMetricsTest {

    @Test
    void countsSuccessAndFailurePerProviderAndCapability() {
        ProviderMetrics m = new ProviderMetrics();
        m.recordSuccess("azure", "mt");
        m.recordSuccess("azure", "mt");
        m.recordFailure("azure", "asr");
        m.recordSuccess("google", "mt");

        assertThat(m.successCount("azure", "mt")).isEqualTo(2);
        assertThat(m.successCount("google", "mt")).isEqualTo(1);
        assertThat(m.failureCount("azure", "asr")).isEqualTo(1);
        assertThat(m.successCount("azure", "asr")).isZero();
    }
}
```

- [ ] **Step 2: 运行确认通过**

```bash
mvn test -Dtest=ProviderMetricsTest
```

Expected: PASS。

- [ ] **Step 3: 全量测试 + Commit**

```bash
mvn test
git add -A
git commit -m "test: lock provider metrics counting semantics"
```

---

## Self-Review

- **Spec 覆盖**：落地 spec §5 的 `detectLanguage/transcribe/translate/synthesize` 四能力接口 + `SpeechProvider` 组合 + 多供应商路由/失败降级（spec §5.2）+ 成本计量（spec §5.2 成本拆分）。真实 Azure/Google 适配器是**已知后置项**（spec §5 前置验证项要求选型实测 LID 置信度可获取性），非遗漏，联调前单独 plan 交付。
- **占位符扫描**：无 TBD/TODO；所有 step 含实际代码。
- **类型一致性**：`MockSpeechProvider`/`ProviderRouter` 均实现 `SpeechProvider`；`ProviderRouterTest` 用 `mock(SpeechProvider.class)` + `when(primary.name()).thenReturn("azure")` 保证 `metrics.recordSuccess(primary.name(),...)` 不 NPE；`ProviderMetrics.Key` 用 value-based `equals/hashCode` 保证 `ConcurrentHashMap` 命中。

### 已知边界（联调前处理，非本 plan 范围）

- Azure/Google 真实适配器（含 LID 置信度可获取性实测、候选语种覆盖验证）——联调前单独 plan。
- 音频编解码/采样率（16/24/48k）、流式分片——由 P4/P3 与 ODM 端云协议冻结，本 plan 只透传 `byte[]`。
- 供应商超时/重试预算（spec 附录 B 第 5 条）——联调时在真实适配器内实现，Mock/Router 不涉及网络超时。
