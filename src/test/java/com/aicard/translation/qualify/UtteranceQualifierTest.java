package com.aicard.translation.qualify;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UtteranceQualifierTest {

    private static final Set<String> WHITELIST = Set.of("ok", "yes", "no", "thank you", "thanks", "sorry", "hello", "hi",
            "ありがとう", "はい", "いいえ", "谢谢", "是", "不是", "네", "감사합니다");
    private static final Set<String> BRANDS = Set.of("iphone", "dyson");
    private static final UtteranceQualifier Q = new UtteranceQualifier(200, true);

    private UtteranceQuality qualify(String text, Long duration) {
        return Q.qualify(text, duration, WHITELIST, BRANDS);
    }

    @Test
    void numericTextIsFiltered() {
        assertThat(qualify("12345", 1000L)).isEqualTo(UtteranceQuality.NUMERIC);
        assertThat(qualify("3.5%", 1000L)).isEqualTo(UtteranceQuality.NUMERIC);
        assertThat(qualify("¥ 1,200", 1000L)).isEqualTo(UtteranceQuality.NUMERIC);
    }

    @Test
    void brandTermContainsIsFiltered() {
        assertThat(qualify("iPhone 15", 1000L)).isEqualTo(UtteranceQuality.BRAND);
    }

    @Test
    void whitelistExactMatchIsFiltered() {
        assertThat(qualify("OK", 1000L)).isEqualTo(UtteranceQuality.WHITELIST);
        assertThat(qualify("Thank you", 1000L)).isEqualTo(UtteranceQuality.WHITELIST);
        assertThat(qualify("  ありがとう  ", 1000L)).isEqualTo(UtteranceQuality.WHITELIST);
    }

    @Test
    void shortDurationOrBlankTextIsShort() {
        assertThat(qualify("こんにちは", 50L)).isEqualTo(UtteranceQuality.SHORT);
        assertThat(qualify("", 1000L)).isEqualTo(UtteranceQuality.SHORT);
        assertThat(qualify(null, 1000L)).isEqualTo(UtteranceQuality.SHORT);
    }

    @Test
    void normalSentenceIsNormal() {
        assertThat(qualify("朝食は何時ですか", 1000L)).isEqualTo(UtteranceQuality.NORMAL);
    }

    @Test
    void disabledMinDurationSkipsShortGate() {
        UtteranceQualifier q = new UtteranceQualifier(-1, true);
        assertThat(q.qualify("こんにちは", 10L, WHITELIST, BRANDS)).isEqualTo(UtteranceQuality.NORMAL);
    }

    @Test
    void disabledNumericFilterSkipsNumericGate() {
        UtteranceQualifier q = new UtteranceQualifier(200, false);
        assertThat(q.qualify("12345", 1000L, WHITELIST, BRANDS)).isEqualTo(UtteranceQuality.NORMAL);
    }
}
