package com.aicard.skb.provider.mock;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.LLMProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 可编程 Mock LLM：返回预设答案 + 引用，测试/开发用。 */
public class MockLlmProvider implements LLMProvider {

    private final AtomicReference<LlmResult> result =
            new AtomicReference<>(new LlmResult("暂无明确说明", List.of()));

    public void setResult(LlmResult r) { result.set(r); }

    @Override
    public LlmResult generate(String question, List<RetrievedChunk> context) {
        return result.get();
    }
}
