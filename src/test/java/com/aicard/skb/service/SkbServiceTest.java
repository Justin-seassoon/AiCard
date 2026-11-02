package com.aicard.skb.service;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.model.SkbResult;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.provider.mock.MockEmbeddingProvider;
import com.aicard.skb.provider.mock.MockLlmProvider;
import com.aicard.skb.seed.KeywordExtractor;
import com.aicard.skb.store.KnowledgeStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkbServiceTest {

    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final MockEmbeddingProvider embeddings = new MockEmbeddingProvider();
    private final MockLlmProvider llm = new MockLlmProvider();
    private final KeywordExtractor keywordExtractor = new KeywordExtractor();
    private final SkbService service = new SkbService(embeddings, store, llm, keywordExtractor, 0.7, 0.45, 0.7);

    @Test
    void localizedAnswerGeneratedInOneCall() {
        embeddings.setVector(List.of(1.0f, 0.0f));
        RetrievedChunk hit = new RetrievedChunk(42L, "朝食 7:00-10:00", "朝食FAQ", "v1", 0.50, List.of(1.0f, 0.0f));
        when(store.searchSimilar(anyLong(), anyLong(), anyString(), anyList(), anyInt())).thenReturn(List.of(hit));

        LLMProvider localizedLlm = mock(LLMProvider.class);
        when(localizedLlm.generateLocalized(anyString(), anyList(), anyString()))
                .thenReturn(new LLMProvider.LocalizedLlmResult(
                        "朝食は7時からです", "早餐7点开始。", List.of(42L)));
        SkbService localizedService = new SkbService(embeddings, store, localizedLlm,
                keywordExtractor, 0.7, 0.45, 0.7);

        SkbService.LocalizedAnswer r = localizedService.answerLocalized("早餐几点", 1L, 2L, "skb", "zh-CN");

        assertThat(r.result().status()).isEqualTo("ok");
        assertThat(r.result().answer()).isEqualTo("朝食は7時からです");
        assertThat(r.localizedAnswer()).isEqualTo("早餐7点开始。");
        verify(localizedLlm, never()).translate(anyString(), anyString());
    }

    @Test
    void crossLingualUsesLowerThreshold() {
        embeddings.setVector(List.of(1.0f, 0.0f));
        RetrievedChunk hit = new RetrievedChunk(42L, "朝食 7:00-10:00", "朝食FAQ", "v1", 0.50, List.of(1.0f, 0.0f));
        when(store.searchSimilar(anyLong(), anyLong(), anyString(), anyList(), anyInt())).thenReturn(List.of(hit));
        llm.setResult(new LlmResult("朝食は7時からです", List.of(42L)));

        assertThat(service.answer("早餐几点", 1L, 2L, "skb").status()).isEqualTo("no_match");
        assertThat(service.answerCrossLingual("早餐几点", 1L, 2L, "skb").status()).isEqualTo("ok");
    }

    @Test
    void answersWithValidCitations() {
        embeddings.setVector(List.of(1.0f, 0.0f));
        RetrievedChunk hit = new RetrievedChunk(42L, "早餐 7:00-10:00", "早餐FAQ", "v1", 0.95, List.of(1.0f, 0.0f));
        when(store.searchSimilar(anyLong(), anyLong(), anyString(), anyList(), anyInt())).thenReturn(List.of(hit));
        llm.setResult(new LlmResult("早餐 7:00-10:00", List.of(42L)));

        SkbResult r = service.answer("早餐几点", 1L, 2L, "skb");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.answer()).isEqualTo("早餐 7:00-10:00");
        assertThat(r.sourceRefs()).isNotEmpty();
    }

    @Test
    void noMatchWhenRetrievalEmpty() {
        embeddings.setVector(List.of(1.0f, 0.0f));
        when(store.searchSimilar(anyLong(), anyLong(), anyString(), anyList(), anyInt())).thenReturn(List.of());

        SkbResult r = service.answer("无答案问题", 1L, 2L, "skb");

        assertThat(r.status()).isEqualTo("no_match");
    }

    @Test
    void blocksFabricatedCitation() {
        embeddings.setVector(List.of(1.0f, 0.0f));
        RetrievedChunk hit = new RetrievedChunk(42L, "早餐", "早餐FAQ", "v1", 0.95, List.of(1.0f, 0.0f));
        when(store.searchSimilar(anyLong(), anyLong(), anyString(), anyList(), anyInt())).thenReturn(List.of(hit));
        llm.setResult(new LlmResult("编造答案", List.of(999L))); // 引用不存在的片段

        SkbResult r = service.answer("早餐几点", 1L, 2L, "skb");

        assertThat(r.status()).isEqualTo("no_match");
    }

    @Test
    void fallsBackToKeywordsWhenVectorMisses() {
        embeddings.setVector(List.of(1.0f, 0.0f));
        when(store.searchSimilar(anyLong(), anyLong(), anyString(), anyList(), anyInt())).thenReturn(List.of());
        RetrievedChunk hit = new RetrievedChunk(42L, "Q: 朝食は何時からですか A: 朝食は6時半からです", "餐饮", "v1", 2.0, List.of(1.0f, 0.0f));
        when(store.searchByKeywords(anyLong(), anyLong(), anyString(), anyList(), anyInt())).thenReturn(List.of(hit));
        llm.setResult(new LlmResult("朝食は6時半からです", List.of(42L)));

        SkbResult r = service.answer("朝食は何時からですか", 1L, 2L, "skb");

        assertThat(r.status()).isEqualTo("ok");
    }
}
