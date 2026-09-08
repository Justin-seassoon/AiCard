package com.aicard.skb.model;

import java.util.List;

/** 员工知识库问答结果。status: ok（有依据回答）/ no_match（无依据，明确回「暂无明确说明」）。 */
public record SkbResult(String status, String answer, List<String> sourceRefs) {
    public static SkbResult noMatch() {
        return new SkbResult("no_match", "知識ベースに明確な記載がありません", List.of());
    }

    public static SkbResult ok(String answer, List<String> sourceRefs) {
        return new SkbResult("ok", answer, sourceRefs);
    }
}
