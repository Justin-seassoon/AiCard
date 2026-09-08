package com.aicard.skb.model;

/** 向量检索命中的切片，带来源文档标题/版本与余弦相似度。 */
public record RetrievedChunk(Long chunkId, String text, String documentTitle, String version,
                             double similarity) {}
