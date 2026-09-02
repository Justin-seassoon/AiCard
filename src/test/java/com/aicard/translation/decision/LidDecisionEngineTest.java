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
        DirectionDecision d1 = engine.decide(in("counterparty", "en-US", 0.9, "ja-JP"));
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
