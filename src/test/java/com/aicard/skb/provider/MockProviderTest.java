package com.aicard.skb.provider;

import com.aicard.skb.model.LlmResult;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.mock.MockEmbeddingProvider;
import com.aicard.skb.provider.mock.MockLlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockProviderTest {

    @Test
    void embeddingReturnsConfiguredVector() {
        MockEmbeddingProvider p = new MockEmbeddingProvider();
        p.setVector(List.of(0.1f, 0.2f));
        assertThat(p.embed("hello")).containsExactly(0.1f, 0.2f);
    }

    @Test
    void embeddingDefaultHasFullDimension() {
        MockEmbeddingProvider p = new MockEmbeddingProvider();
        assertThat(p.embed("x")).hasSize(MockEmbeddingProvider.DIM);
    }

    @Test
    void llmReturnsConfiguredAnswerWithCitations() {
        MockLlmProvider p = new MockLlmProvider();
        p.setResult(new LlmResult("早餐 7:00-10:00", List.of(42L)));
        RetrievedChunk c = new RetrievedChunk(42L, "早餐时间", "FAQ", "v1", 0.9);
        LlmResult r = p.generate("早餐几点", List.of(c));
        assertThat(r.answer()).isEqualTo("早餐 7:00-10:00");
        assertThat(r.citedChunkIds()).containsExactly(42L);
    }
}
