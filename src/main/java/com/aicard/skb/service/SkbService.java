package com.aicard.skb.service;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.store.KnowledgeStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 受控 RAG 编排（「检索 → 生成 → 校验」三段，spec §9）：
 * 检索用 pgvector 余弦（强制 tenant filter）；生成用 LLM（唯一一次调用，引用必须选自命中片段）；
 * 校验用非 LLM 手段（引用 ID ∈ 命中集合 + 答案/片段向量相似度）。无依据 → noMatch 兜底不编造。
 *
 * 纯 POJO，由 {@code SkbConfig} 装配（阈值通过 @Value 注入）。
 */
public class SkbService {

    private static final int TOP_K = 5;

    private final EmbeddingProvider embeddings;
    private final KnowledgeStore store;
    private final LLMProvider llm;
    private final double thetaRetr;
    private final double thetaCheck;

    public SkbService(EmbeddingProvider embeddings, KnowledgeStore store, LLMProvider llm,
                      double thetaRetr, double thetaCheck) {
        this.embeddings = embeddings;
        this.store = store;
        this.llm = llm;
        this.thetaRetr = thetaRetr;
        this.thetaCheck = thetaCheck;
    }

    public SkbResult answer(String question, Long customerId, Long storeId, String domain) {
        // ① 检索段
        List<Float> qVec = embeddings.embed(question);
        List<RetrievedChunk> hits = store.searchSimilar(customerId, storeId, domain, qVec, TOP_K);
        if (hits.isEmpty() || hits.get(0).similarity() < thetaRetr) {
            return SkbResult.noMatch();
        }

        // ② 生成段（唯一一次 LLM 调用）
        LlmResult llmOut = llm.generate(question, hits);

        // ③ 校验段（非 LLM）：引用必须 ⊆ 命中集合，答案与命中片段相似度达标
        Set<Long> hitIds = new HashSet<>();
        hits.forEach(c -> hitIds.add(c.chunkId()));
        boolean citedValid = !llmOut.citedChunkIds().isEmpty()
                && hitIds.containsAll(llmOut.citedChunkIds());
        if (!citedValid) {
            return SkbResult.noMatch();
        }

        List<RetrievedChunk> cited = hits.stream()
                .filter(c -> llmOut.citedChunkIds().contains(c.chunkId())).toList();
        double sim = maxSimilarity(llmOut.answer(), cited);
        if (sim < thetaCheck) {
            return SkbResult.noMatch();
        }

        List<String> sourceRefs = cited.stream()
                .map(c -> c.documentTitle() + "@" + c.version() + "#" + c.chunkId()).toList();
        return SkbResult.ok(llmOut.answer(), sourceRefs);
    }

    private double maxSimilarity(String answer, List<RetrievedChunk> cited) {
        if (cited.isEmpty()) {
            return 0.0;
        }
        List<Float> aVec = embeddings.embed(answer);
        double max = 0.0;
        for (RetrievedChunk c : cited) {
            if (c.answerEmbedding() == null) {
                continue; // 旧数据未回填答案向量，跳过
            }
            max = Math.max(max, cosine(aVec, c.answerEmbedding()));
        }
        return max;
    }

    private double cosine(List<Float> a, List<Float> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
