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

    @Test
    void applySetsBothVisitorAndPending() {
        TranslationSessionState s = new TranslationSessionState();
        s.lock("ja-JP");
        s.apply("ja-JP", new PendingSwitch("en-US", 1));
        assertThat(s.visitorLanguage()).isEqualTo("ja-JP");
        assertThat(s.pending()).isEqualTo(new PendingSwitch("en-US", 1));
    }
}
