package com.aicard.skb.store;

import com.aicard.skb.model.Chunk;
import com.aicard.skb.model.Document;
import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.mock.MockEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** pgvector 余弦检索集成测试：验证命中排序 + 跨租户隔离（另一租户 chunk 不返回）。 */
@SpringBootTest
@Testcontainers
class KnowledgeStoreTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired KnowledgeStore store;

    @Test
    void searchesSimilarChunksWithinTenant() {
        Document doc = store.insertDocument(new Document(null, 1L, 2L, "早餐FAQ", "v1", "published", Instant.now()));
        store.insertChunk(new Chunk(null, doc.id(), 1L, 2L, 0, "早餐时间为 7:00-10:00"), MockEmbeddingProvider.oneHot(0));
        store.insertChunk(new Chunk(null, doc.id(), 9L, 9L, 1, "另一租户早餐时间"), MockEmbeddingProvider.oneHot(0));

        List<RetrievedChunk> hits = store.searchSimilar(1L, 2L, MockEmbeddingProvider.oneHot(0), 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).documentTitle()).isEqualTo("早餐FAQ");
    }
}
