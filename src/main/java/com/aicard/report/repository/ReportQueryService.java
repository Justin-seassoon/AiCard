package com.aicard.report.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** 报表聚合查询：按设备 + 周期聚合 translation_sample 与 qa_log（租户显式传参）。 */
@Repository
public class ReportQueryService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ReportQueryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public DeviceAggregate aggregate(Long customerId, Long storeId, String deviceId,
                                     LocalDate start, LocalDate end) {
        OffsetDateTime from = start.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime to = end.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        SqlRowSet usage = jdbc.queryForRowSet(
                "SELECT COUNT(*) AS turn_count, COUNT(DISTINCT session_id) AS session_count, " +
                "COUNT(DISTINCT created_at::date) AS active_days FROM translation_sample " +
                "WHERE customer_id=? AND store_id=? AND device_id=? AND created_at>=? AND created_at<?",
                customerId, storeId, deviceId, from, to);
        usage.next();
        int turnCount = usage.getInt("turn_count");
        int sessionCount = usage.getInt("session_count");
        int activeDays = usage.getInt("active_days");

        Map<String, Integer> langs = new LinkedHashMap<>();
        jdbc.query(
                "SELECT tgt_lang, COUNT(*) AS c FROM translation_sample " +
                "WHERE customer_id=? AND store_id=? AND device_id=? AND created_at>=? AND created_at<? " +
                "GROUP BY tgt_lang",
                rs -> {
                    langs.put(rs.getString("tgt_lang"), rs.getInt("c"));
                },
                customerId, storeId, deviceId, from, to);

        Integer qaHitCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qa_log WHERE customer_id=? AND store_id=? AND device_id=? " +
                "AND status='ok' AND created_at>=? AND created_at<?",
                Integer.class, customerId, storeId, deviceId, from, to);

        String metrics;
        try {
            metrics = mapper.writeValueAsString(Map.of("langs", langs));
        } catch (JsonProcessingException e) {
            metrics = "{}";
        }
        return new DeviceAggregate(turnCount, sessionCount,
                qaHitCount == null ? 0 : qaHitCount, activeDays, metrics);
    }
}
