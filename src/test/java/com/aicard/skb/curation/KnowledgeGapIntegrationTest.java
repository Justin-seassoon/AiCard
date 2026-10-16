package com.aicard.skb.curation;

import com.aicard.skb.model.RetrievedChunk;
import com.aicard.skb.provider.mock.MockEmbeddingProvider;
import com.aicard.skb.store.KnowledgeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ① 自生长知识 端到端集成测试（真跑 pgvector 容器）：
 * {@code qa_log(no_match)} → 捕获（聚合成 1 条）→ 起草 → 人工审核入库 → 新知识可被检索。
 */
@SpringBootTest
@Testcontainers
class KnowledgeGapIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired GapCaptureService capture;
    @Autowired CandidateDraftService draft;
    @Autowired KnowledgeGapController controller;
    @Autowired GapStore gaps;
    @Autowired KnowledgeStore store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void noMatchToKnowledgeLoop() {
        long c = 41L, s = 42L;

        // 1) 造两条 no_match 问答（同问题不同标点 → 应聚合）
        insertNoMatch(c, s, "朝食は何時ですか？");
        insertNoMatch(c, s, "朝食は何時ですか");

        // 2) 捕获 → 1 条缺口、occurrences=2
        assertThat(capture.capture(c, s, "skb")).isEqualTo(1);
        List<KnowledgeGap> list = gaps.list(c, s, "skb", null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).occurrences()).isEqualTo(2);
        assertThat(list.get(0).status()).isEqualTo(KnowledgeGap.STATUS_OPEN);

        long id = list.get(0).id();

        // 3) 起草 → 落 draft（Mock LLM 返回固定草稿）
        KnowledgeGap drafted = draft.draft(id, c, s, "skb");
        assertThat(drafted.status()).isEqualTo(KnowledgeGap.STATUS_DRAFTED);
        assertThat(drafted.draftAnswer()).isNotBlank();

        // 4) 人工审核通过 → 入库（document=published + chunk）
        controller.approve(id, new KnowledgeGapController.ApproveRequest("朝食は 7:00 からです"), c, s, "skb");
        assertThat(gaps.find(id, c, s).orElseThrow().status()).isEqualTo(KnowledgeGap.STATUS_APPROVED);
        assertThat(store.listDocuments(c, s, "skb")).hasSize(1);

        // 5) 入库后新知识**可被检索命中**
        List<RetrievedChunk> hits = store.searchSimilar(c, s, "skb", MockEmbeddingProvider.oneHot(0), 5);
        assertThat(hits).isNotEmpty();
    }

    private void insertNoMatch(long customerId, long storeId, String question) {
        jdbc.update("INSERT INTO qa_log(customer_id, store_id, source, question, status) VALUES (?,?,?,?, 'no_match')",
                customerId, storeId, "skb", question);
    }
}
