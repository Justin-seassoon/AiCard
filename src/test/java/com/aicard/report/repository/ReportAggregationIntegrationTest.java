package com.aicard.report.repository;

import com.aicard.report.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** C4 聚合泛化：deviceId 可空（null = 租户级）。 */
@SpringBootTest
@Testcontainers
class ReportAggregationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ReportService reports;
    @Autowired JdbcTemplate jdbc;

    @Test
    void deviceNullAggregatesWholeTenant() {
        long c = 91L, s = 92L;
        sample(c, s, "devA", "sess1");
        sample(c, s, "devA", "sess1"); // 同 session 第二次 → turnCount+1, sessionCount 不增
        sample(c, s, "devB", "sess2");

        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        DeviceAggregate tenant = reports.aggregate(c, s, null, start, end);
        assertThat(tenant.turnCount()).isEqualTo(3);      // 跨设备
        assertThat(tenant.sessionCount()).isEqualTo(2);

        assertThat(reports.aggregate(c, s, "devA", start, end).turnCount()).isEqualTo(2);
        assertThat(reports.aggregate(c, s, "devB", start, end).turnCount()).isEqualTo(1);
    }

    private void sample(long customerId, long storeId, String deviceId, String sessionId) {
        jdbc.update("INSERT INTO translation_sample(customer_id, store_id, device_id, session_id, turn_id) " +
                "VALUES (?,?,?,?,?)", customerId, storeId, deviceId, sessionId, "t" + System.nanoTime());
    }
}
