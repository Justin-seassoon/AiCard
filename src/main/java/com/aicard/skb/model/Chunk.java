package com.aicard.skb.model;

/** 文档切片，embedding 为 1536 维向量（联调按真实 embedding 模型校准）。 */
public record Chunk(Long id, Long documentId, Long customerId, Long storeId, int chunkIndex,
                    String text) {}
