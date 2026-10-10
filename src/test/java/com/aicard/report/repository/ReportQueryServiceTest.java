package com.aicard.report.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportQueryServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportQueryService service = new ReportQueryService(jdbc, mapper);

    @Test
    void aggregatesCountsAndLanguages() {
        SqlRowSet usage = mock(SqlRowSet.class);
        when(usage.next()).thenReturn(true);
        when(usage.getInt("turn_count")).thenReturn(5);
        when(usage.getInt("session_count")).thenReturn(2);
        when(usage.getInt("active_days")).thenReturn(3);
        when(jdbc.queryForRowSet(anyString(), any(Object[].class))).thenReturn(usage);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(7);

        DeviceAggregate agg = service.aggregate(10L, 20L, "dev-a",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8));

        assertThat(agg.turnCount()).isEqualTo(5);
        assertThat(agg.sessionCount()).isEqualTo(2);
        assertThat(agg.activeDays()).isEqualTo(3);
        assertThat(agg.qaHitCount()).isEqualTo(7);
        assertThat(agg.metrics()).contains("langs");
    }
}
