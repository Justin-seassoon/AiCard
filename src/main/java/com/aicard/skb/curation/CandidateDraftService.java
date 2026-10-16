package com.aicard.skb.curation;

import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.store.KnowledgeStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 候选起草（① 自生长知识）：为一条缺口**召回相近知识 → LLM 起草候选答案 → 落 draft**。
 *
 * <p>「相近」= 直接用现有检索取 top-K（**不设阈值**，即便未达命中门槛也取最近的片段作依据）；
 * 无相近内容时由 LLM 输出「候補・要確認」模板。<b>草稿一律要人工审核后才入库。</b>
 */
@Service
public class CandidateDraftService {

    private static final int RELATED_TOP_K = 5;

    private final EmbeddingProvider embeddings;
    private final KnowledgeStore store;
    private final LLMProvider llm;
    private final GapStore gaps;

    public CandidateDraftService(EmbeddingProvider embeddings, KnowledgeStore store, LLMProvider llm, GapStore gaps) {
        this.embeddings = embeddings;
        this.store = store;
        this.llm = llm;
        this.gaps = gaps;
    }

    public KnowledgeGap draft(long id, long customerId, long storeId, String domain) {
        KnowledgeGap gap = gaps.find(id, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "gap not found"));

        List<Float> qVec = embeddings.embed(gap.question());
        List<RetrievedChunk> related = store.searchSimilar(customerId, storeId, domain, qVec, RELATED_TOP_K);
        String answer = llm.draftAnswer(gap.question(), related);
        String refs = related.stream()
                .map(c -> c.documentTitle() + "@" + c.version() + "#" + c.chunkId())
                .collect(Collectors.joining(","));

        gaps.updateDraft(id, customerId, storeId, answer, refs.isEmpty() ? null : refs);
        return gaps.find(id, customerId, storeId).orElse(gap);
    }
}
