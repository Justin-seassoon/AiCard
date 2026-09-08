package com.aicard.skb.provider;

import java.util.List;

/** 文本向量化（embedding）。demo 用 Mock，真实 Azure OpenAI 后置。 */
public interface EmbeddingProvider {
    List<Float> embed(String text);
}
