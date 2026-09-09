package com.aicard.skb.model;

import java.util.List;

/** 向量检索命中的切片，带来源文档标题/版本、余弦相似度与答案向量（校验段用）。 */
public record RetrievedChunk(Long chunkId, String text, String documentTitle, String version,
                             double similarity, List<Float> answerEmbedding) {}
