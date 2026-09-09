package com.aicard.translation.qualify;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UtteranceQualifierTest {

    private static final UtteranceQualifier DEFAULT = new UtteranceQualifier(
            200, true, Set.of("ok", "yes", "no", "thank you", "thanks", "sorry", "hello", "hi",
                    "ありがとう", "はい", "いいえ", "谢谢", "是", "不是", "네", "감사합니다"), Set.of());

    @Test
    void numericTextIsFiltered() {
        assertThat(DEFAULT.qualify("12345", 1000L)).isEqualTo(UtteranceQuality.NUMERIC);
        assertThat(DEFAULT.qualify("3.5%", 1000L)).isEqualTo(UtteranceQuality.NUMERIC);
        assertThat(DEFAULT.qualify("¥ 1,200", 1000L)).isEqualTo(UtteranceQuality.NUMERIC);
    }

    @Test
    void brandTermContainsIsFiltered() {
        UtteranceQualifier q = new UtteranceQualifier(200, true, Set.of(), Set.of("iphone", "dyson"));
        assertThat(q.qualify("iPhone 15", 1000L)).isEqualTo(UtteranceQuality.BRAND);
    }

    @Test
    void whitelistExactMatchIsFiltered() {
        assertThat(DEFAULT.qualify("OK", 1000L)).isEqualTo(UtteranceQuality.WHITELIST);
        assertThat(DEFAULT.qualify("Thank you", 1000L)).isEqualTo(UtteranceQuality.WHITELIST);
        assertThat(DEFAULT.qualify("  ありがとう  ", 1000L)).isEqualTo(UtteranceQuality.WHITELIST);
    }

    @Test
    void shortDurationOrBlankTextIsShort() {
        assertThat(DEFAULT.qualify("こんにちは", 50L)).isEqualTo(UtteranceQuality.SHORT);
        assertThat(DEFAULT.qualify("", 1000L)).isEqualTo(UtteranceQuality.SHORT);
        assertThat(DEFAULT.qualify(null, 1000L)).isEqualTo(UtteranceQuality.SHORT);
    }

    @Test
    void normalSentenceIsNormal() {
        assertThat(DEFAULT.qualify("朝食は何時ですか", 1000L)).isEqualTo(UtteranceQuality.NORMAL);
    }

    @Test
    void disabledMinDurationSkipsShortGate() {
        UtteranceQualifier q = new UtteranceQualifier(-1, true, Set.of(), Set.of());
        assertThat(q.qualify("こんにちは", 10L)).isEqualTo(UtteranceQuality.NORMAL);
    }

    @Test
    void disabledNumericFilterSkipsNumericGate() {
        UtteranceQualifier q = new UtteranceQualifier(200, false, Set.of(), Set.of());
        assertThat(q.qualify("12345", 1000L)).isEqualTo(UtteranceQuality.NORMAL);
    }
}
