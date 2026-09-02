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
