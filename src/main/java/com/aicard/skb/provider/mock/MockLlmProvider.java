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

    public void setResult(LlmResult r) { result.set(r); }
    public void setTranslatedText(String t) { translatedText.set(t); }

    @Override
    public LlmResult generate(String question, List<RetrievedChunk> context) {
        return result.get();
    }

    @Override
    public String translate(String text, String targetLang) {
        return translatedText.get();
    }
}
