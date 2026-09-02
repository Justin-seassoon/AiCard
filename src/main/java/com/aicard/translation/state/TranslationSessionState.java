package com.aicard.translation.state;

/**
 * 翻译会话的软锁定语言记忆：visitor_language（UNKNOWN 或具体语种）+ 待切换计数 pending。
 */
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

    /** 一次性应用决策引擎输出的新记忆（visitor_language + pending）。 */
    public void apply(String visitorLanguage, PendingSwitch pending) {
        this.visitorLanguage = visitorLanguage;
        this.pending = pending;
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
