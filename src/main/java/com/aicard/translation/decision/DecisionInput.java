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
