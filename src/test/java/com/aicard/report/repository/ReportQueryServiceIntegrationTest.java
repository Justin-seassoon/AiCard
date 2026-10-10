package com.aicard.report.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 在真 PostgreSQL 上验证 ReportQueryService 聚合 SQL 的列名与统计口径（Mock 单测无法覆盖）。 */
@SpringBootTest
@Testcontainers
class ReportQueryServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard")
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ReportQueryService service;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM qa_log");
        jdbc.update("DELETE FROM translation_sample");
    }

    @Test
    void aggregatesOnRealPostgres() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 3);
        Timestamp t1 = Timestamp.from(LocalDate.of(2026, 10, 1).atTime(12, 0).toInstant(ZoneOffset.UTC));
        Timestamp t2 = Timestamp.from(LocalDate.of(2026, 10, 2).atTime(12, 0).toInstant(ZoneOffset.UTC));

        // 3 轮 / 2 会话 / 2 天 / 2 语种
        insertSample("sess-1", "turn-1", "ja-JP", t1);
        insertSample("sess-1", "turn-2", "en-US", t1);
        insertSample("sess-2", "turn-1", "ja-JP", t2);

        // 2 条命中 + 1 条未命中（不计入 qa_hit_count）
        insertQa("skb", "ok", t1);
        insertQa("vkb", "ok", t2);
        insertQa("skb", "no_match", t1);

        DeviceAggregate agg = service.aggregate(10L, 20L, "dev-a", start, end);

        assertThat(agg.turnCount()).isEqualTo(3);
        assertThat(agg.sessionCount()).isEqualTo(2);
        assertThat(agg.activeDays()).isEqualTo(2);
        assertThat(agg.qaHitCount()).isEqualTo(2);
        assertThat(agg.metrics()).contains("langs").contains("ja-JP").contains("en-US");
    }

    private void insertSample(String sessionId, String turnId, String tgtLang, Timestamp ts) {
        jdbc.update("INSERT INTO translation_sample (customer_id, store_id, session_id, turn_id, device_id, tgt_lang, created_at) " +
                "VALUES (?,?,?,?,?,?,?)", 10L, 20L, sessionId, turnId, "dev-a", tgtLang, ts);
    }

    private void insertQa(String source, String status, Timestamp ts) {
        jdbc.update("INSERT INTO qa_log (customer_id, store_id, device_id, source, status, created_at) " +
                "VALUES (?,?,?,?,?,?)", 10L, 20L, "dev-a", source, status, ts);
    }
}
