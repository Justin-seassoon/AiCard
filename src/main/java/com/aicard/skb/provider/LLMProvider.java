package com.aicard.skb.provider;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;

import java.util.List;

/** 受限生成（LLM）：基于命中片段回答问题，引用必须选自 context 片段 ID。demo 用 Mock。 */
public interface LLMProvider {
    LlmResult generate(String question, List<RetrievedChunk> context);

    /** 文本翻译：把 text 翻译成 targetLang（VKB 答案反向翻译用）。 */
    String translate(String text, String targetLang);
}
