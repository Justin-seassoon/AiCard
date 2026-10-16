package com.aicard.skb.curation;

import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.provider.LLMProvider;
import com.aicard.skb.store.KnowledgeStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateDraftServiceTest {

    @Test
    void draftsCandidateAndRecordsRefsFromRelatedChunks() {
        GapStore gaps = mock(GapStore.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        KnowledgeStore store = mock(KnowledgeStore.class);
        LLMProvider llm = mock(LLMProvider.class);

        KnowledgeGap gap = new KnowledgeGap(7L, 1L, 1L, "skb", "朝食は何時ですか", 3,
                "open", null, null, null, null, null);
        when(gaps.find(7L, 1L, 1L)).thenReturn(Optional.of(gap));
        when(embeddings.embed(anyString())).thenReturn(List.of(0.1f));
        List<RetrievedChunk> related = List.of(
                new RetrievedChunk(11L, "Q: 朝食 A: 7時", "朝食のご案内", "v1", 0.4, List.of()));
        when(store.searchSimilar(eq(1L), eq(1L), eq("skb"), anyList(), anyInt())).thenReturn(related);
        when(llm.draftAnswer(eq("朝食は何時ですか"), anyList())).thenReturn("7時です。※要確認");

        CandidateDraftService svc = new CandidateDraftService(embeddings, store, llm, gaps);
        KnowledgeGap result = svc.draft(7L, 1L, 1L, "skb");

        verify(gaps).updateDraft(7L, 1L, 1L, "7時です。※要確認", "朝食のご案内@v1#11");
        assertThat(result).isNotNull();
    }
}
