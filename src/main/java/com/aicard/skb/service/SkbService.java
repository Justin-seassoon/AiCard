package com.aicard.skb.service;

import com.aicard.skb.cache.QaAnswerCache;
import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.seed.KeywordExtractor;
import com.aicard.skb.store.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(SkbService.class);
    private static final int TOP_K = 5;

    private final EmbeddingProvider embeddings;
    private final KnowledgeStore store;
    private final LLMProvider llm;
    private final KeywordExtractor keywordExtractor;
    private final QaAnswerCache cache;
    private final double thetaRetr;
    private final double thetaRetrCrossLingual;
    private final double thetaCheck;

    public SkbService(EmbeddingProvider embeddings, KnowledgeStore store, LLMProvider llm,
                      KeywordExtractor keywordExtractor, QaAnswerCache cache, double thetaRetr,
                      double thetaRetrCrossLingual, double thetaCheck) {
        this.embeddings = embeddings;
        this.store = store;
        this.llm = llm;
        this.keywordExtractor = keywordExtractor;
        this.cache = cache;
        this.thetaRetr = thetaRetr;
        this.thetaRetrCrossLingual = thetaRetrCrossLingual;
        this.thetaCheck = thetaCheck;
    }

    /** 本地化问答结果：result 里的 answer 始终是日语（供质量校验），localizedAnswer 给员工语言。 */
    public record LocalizedAnswer(SkbResult result, String localizedAnswer) {}

    public SkbResult answer(String question, Long customerId, Long storeId, String domain) {
        return answerInternal(question, customerId, storeId, domain, thetaRetr, null).result();
    }

    /**
     * 跨语言检索：非日语问题与日语知识库的向量相似度天然偏低，用单独的、更低的阈值；
     * 未命中时调用方再翻译成日语重试。
     */
    public SkbResult answerCrossLingual(String question, Long customerId, Long storeId, String domain) {
        return answerInternal(question, customerId, storeId, domain, thetaRetrCrossLingual, null).result();
    }

    /** 一次生成日语答案 + 员工语言答案；localizedAnswer 可能为 null（供应商不支持时回退翻译）。 */
    public LocalizedAnswer answerLocalized(String question, Long customerId, Long storeId,
                                           String domain, String targetLang) {
        return answerInternal(question, customerId, storeId, domain, thetaRetrCrossLingual, targetLang);
    }

    private LocalizedAnswer answerInternal(String question, Long customerId, Long storeId,
                                           String domain, double retrievalThreshold, String targetLang) {
        QaAnswerCache.Key key = QaAnswerCache.key(customerId, storeId, domain, targetLang,
                retrievalThreshold, question);
        var cached = cache.get(key);
        if (cached.isPresent()) {
            log.info("skb cache hit: target={} question={}", targetLang, question);
            return cached.get();
        }
        LocalizedAnswer result = answerUncached(question, customerId, storeId, domain,
                retrievalThreshold, targetLang);
        cache.put(key, result);
        return result;
    }

    private LocalizedAnswer answerUncached(String question, Long customerId, Long storeId,
                                           String domain, double retrievalThreshold, String targetLang) {
        long t0 = System.currentTimeMillis();
        long embedMs = 0;
        long searchMs = 0;
        long llmMs = 0;
        long checkMs = 0;
        boolean keyword = false;
        int hitCount = 0;
        double topSim = 0;
        double checkSim = 0;
        String reason = null;
        try {
            // ① 检索段：向量为主，miss 时关键词兜底
            List<Float> qVec = embeddings.embed(question);
            embedMs = System.currentTimeMillis() - t0;
            long t = System.currentTimeMillis();
            List<RetrievedChunk> hits = store.searchSimilar(customerId, storeId, domain, qVec, TOP_K);
            searchMs = System.currentTimeMillis() - t;
            if (hits.isEmpty() || hits.get(0).similarity() < retrievalThreshold) {
                List<String> kws = keywordExtractor.extract(question);
                keyword = !kws.isEmpty();
                t = System.currentTimeMillis();
                hits = kws.isEmpty() ? List.of()
                        : store.searchByKeywords(customerId, storeId, domain, kws, TOP_K);
                searchMs += System.currentTimeMillis() - t;
            }
            hitCount = hits.size();
            topSim = hits.isEmpty() ? 0 : hits.get(0).similarity();
            if (hits.isEmpty()) {
                reason = "retrieval_empty";
                return new LocalizedAnswer(SkbResult.noMatch(), null);
            }

            // ② 生成段：一次调用同时产出日语答案与员工语言答案（不支持时由调用方回退翻译）
            t = System.currentTimeMillis();
            LlmResult llmOut;
            String localizedAnswer = null;
            if (targetLang == null) {
                llmOut = llm.generate(question, hits);
            } else {
                LLMProvider.LocalizedLlmResult localized = llm.generateLocalized(question, hits, targetLang);
                llmOut = new LlmResult(localized.answer(), localized.citedChunkIds());
                localizedAnswer = localized.localizedAnswer();
            }
            llmMs = System.currentTimeMillis() - t;

            // ③ 校验段（非 LLM）：引用必须 ⊆ 命中集合，答案与命中片段相似度达标
            t = System.currentTimeMillis();
            Set<Long> hitIds = new HashSet<>();
            hits.forEach(c -> hitIds.add(c.chunkId()));
            boolean citedValid = !llmOut.citedChunkIds().isEmpty()
                    && hitIds.containsAll(llmOut.citedChunkIds());
            if (!citedValid) {
                reason = "citation";
                return new LocalizedAnswer(SkbResult.noMatch(), null);
            }

            List<RetrievedChunk> cited = hits.stream()
                    .filter(c -> llmOut.citedChunkIds().contains(c.chunkId())).toList();
            checkSim = maxSimilarity(llmOut.answer(), cited);
            checkMs = System.currentTimeMillis() - t;
            if (checkSim < thetaCheck) {
                reason = "similarity";
                return new LocalizedAnswer(SkbResult.noMatch(), null);
            }

            List<String> sourceRefs = cited.stream()
                    .map(c -> c.documentTitle() + "@" + c.version() + "#" + c.chunkId()).toList();
            return new LocalizedAnswer(SkbResult.ok(llmOut.answer(), sourceRefs), localizedAnswer);
        } finally {
            log.info("skb timings: target={} status={} reason={} top={} sim={} embed_q={}ms search={}ms keyword={} llm={}ms check={}ms hits={} total={}ms",
                    targetLang, reason == null ? "ok" : "no_match", reason,
                    String.format("%.3f", topSim), String.format("%.3f", checkSim),
                    embedMs, searchMs, keyword, llmMs, checkMs, hitCount,
                    System.currentTimeMillis() - t0);
        }
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
