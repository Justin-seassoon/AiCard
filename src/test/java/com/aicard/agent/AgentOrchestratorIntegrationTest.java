package com.aicard.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** ② agent 执行 集成测试：规则模式跑通两个 goal，产出非空文件。 */
@SpringBootTest
@Testcontainers
class AgentOrchestratorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AgentTaskRepository tasks;
    @Autowired AgentOrchestrator orchestrator;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.aicard.skb.provider.LLMProvider llmProvider;

    @Test
    void alertReportProducesDocx() {
        long c = 71L, s = 72L;
        insertOrder(c, s, "O-1");
        insertOrder(c, s, "O-2");
        insertInspection(c, s, "T-1");

        AgentTask done = run(c, s, AgentTask.GOAL_ALERT_REPORT);

        assertThat(done.getStatus()).isEqualTo(AgentTask.STATUS_DONE);
        assertThat(done.getSteps()).contains("list_maintenance_orders")
                .contains("list_inspection_tasks").contains("generate_report_docx");
        assertThat(artifactBytes(done)).isNotEmpty();
    }

    @Test
    void usageReportProducesXlsx() {
        long c = 81L, s = 82L;
        sample(c, s, "devA", "sess1");
        sample(c, s, "devB", "sess2");

        AgentTask done = run(c, s, AgentTask.GOAL_USAGE_REPORT);

        assertThat(done.getStatus()).isEqualTo(AgentTask.STATUS_DONE);
        assertThat(done.getSteps()).contains("usage_summary").contains("generate_usage_xlsx");
        assertThat(artifactBytes(done)).isNotEmpty();
    }

    /** ②-A：自由文本目标 → LLM（Mock）规划 → compose_docx 产物。 */
    @Test
    void freeTextGoalPlannedByLlmProducesDocx() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                llmProvider instanceof com.aicard.skb.provider.mock.MockLlmProvider, "需要 Mock LLM");
        com.aicard.skb.provider.mock.MockLlmProvider mock =
                (com.aicard.skb.provider.mock.MockLlmProvider) llmProvider;
        mock.setPlans(java.util.List.of(
                com.aicard.skb.provider.LLMProvider.PlanStep.tool("compose_docx",
                        "{\"title\":\"社内通知\",\"lines\":[\"11月10日に全社会議を行います。\"]}"),
                com.aicard.skb.provider.LLMProvider.PlanStep.finished()));

        long c = 101L, s = 102L;
        AgentTask task = tasks.save(AgentTask.builder()
                .customerId(c).storeId(s).goal("社内通知の下書きを作って").mode(AgentTask.MODE_AUTO)
                .status(AgentTask.STATUS_PENDING).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        orchestrator.run(task.getId());

        AgentTask done = tasks.findById(task.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(AgentTask.STATUS_DONE);
        assertThat(done.getArtifactName()).isEqualTo("composed-doc.docx");
        assertThat(artifactBytes(done)).isNotEmpty();
    }

    private AgentTask run(long customerId, long storeId, String goal) {
        AgentTask task = tasks.save(AgentTask.builder()
                .customerId(customerId).storeId(storeId).goal(goal).mode(AgentTask.MODE_RULE)
                .status(AgentTask.STATUS_PENDING).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        orchestrator.run(task.getId()); // 同步直接跑（@Async 只在 Runner 上）
        return tasks.findById(task.getId()).orElseThrow();
    }

    private static byte[] artifactBytes(AgentTask t) {
        assertThat(t.getArtifactBase64()).isNotBlank();
        return Base64.getDecoder().decode(t.getArtifactBase64());
    }

    private void insertOrder(long c, long s, String no) {
        jdbc.update("INSERT INTO maintenance_order(order_no, title, status, customer_id, store_id, created_at) " +
                "VALUES (?,?,?,?,?, now())", no, "漏水", "open", c, s);
    }

    private void insertInspection(long c, long s, String no) {
        jdbc.update("INSERT INTO inspection_task(task_no, title, checklist, status, customer_id, store_id, created_at) " +
                "VALUES (?,?,?::jsonb,?,?,?, now())", no, "点検", "{}", "pending", c, s);
    }

    private void sample(long c, long s, String dev, String sess) {
        jdbc.update("INSERT INTO translation_sample(customer_id, store_id, device_id, session_id, turn_id) " +
                "VALUES (?,?,?,?,?)", c, s, dev, sess, "t" + System.nanoTime());
    }
}
