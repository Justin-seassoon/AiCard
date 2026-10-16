package com.aicard.skb.provider.mock;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.LLMProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 可编程 Mock LLM：返回预设答案 + 引用 / 预设翻译，测试/开发用。 */
public class MockLlmProvider implements LLMProvider {

    private final AtomicReference<LlmResult> result =
            new AtomicReference<>(new LlmResult("暂无明确说明", List.of()));
    private final AtomicReference<String> translatedText = new AtomicReference<>("こんにちは");
    private final AtomicReference<String> draft = new AtomicReference<>("（候補・要確認）");
    private final AtomicReference<String> document = new AtomicReference<>("（下書き・要確認）");
    private final AtomicReference<PlanStep> plan = new AtomicReference<>(PlanStep.finished());

    public void setResult(LlmResult r) { result.set(r); }
    public void setTranslatedText(String t) { translatedText.set(t); }
    public void setDraft(String d) { draft.set(d); }
    public void setDocument(String d) { document.set(d); }
    public void setPlan(PlanStep p) { plan.set(p); }

    @Override
    public LlmResult generate(String question, List<RetrievedChunk> context) {
        return result.get();
    }

    @Override
    public String translate(String text, String targetLang) {
        return translatedText.get();
    }

    @Override
    public String draftAnswer(String question, List<RetrievedChunk> related) {
        return draft.get();
    }

    @Override
    public String draftDocument(String instruction) {
        return document.get();
    }

    @Override
    public PlanStep planStep(String goal, List<String> toolSpecs, List<String> history) {
        return plan.get();
    }
}
