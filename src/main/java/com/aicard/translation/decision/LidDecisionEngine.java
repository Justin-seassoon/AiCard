package com.aicard.translation.decision;

import com.aicard.translation.state.PendingSwitch;

/**
 * LID 软锁定决策引擎（纯函数，无副作用）：输入 source_side + LID 结果 + 记忆，
 * 输出方向（src/tgt）、动作（translate/repeat/conflict/no_translate）与新记忆。
 * 见架构 spec §7.4。
 */
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
