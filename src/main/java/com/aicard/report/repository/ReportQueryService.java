package com.aicard.report.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表聚合查询：按周期聚合 {@code translation_sample} 与 {@code qa_log}（租户显式传参）。
 *
 * <p><b>C4 聚合泛化</b>：{@code deviceId} 可空——为 null/空串时聚合**整个租户**（不按设备过滤），
 * 供腿 B（无设备）与租户级导出使用。
 */
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
        boolean scoped = deviceId != null && !deviceId.isBlank();
        String devFilter = scoped ? "AND device_id=? " : "";

        List<Object> scope = new ArrayList<>();
        scope.add(customerId);
        scope.add(storeId);
        if (scoped) {
            scope.add(deviceId);
        }

        SqlRowSet usage = jdbc.queryForRowSet(
                "SELECT COUNT(*) AS turn_count, COUNT(DISTINCT session_id) AS session_count, " +
                        "COUNT(DISTINCT created_at::date) AS active_days FROM translation_sample " +
                        "WHERE customer_id=? AND store_id=? " + devFilter + "AND created_at>=? AND created_at<?",
                withRange(scope, from, to));
        usage.next();
        int turnCount = usage.getInt("turn_count");
        int sessionCount = usage.getInt("session_count");
        int activeDays = usage.getInt("active_days");

        Map<String, Integer> langs = new LinkedHashMap<>();
        jdbc.query(
                "SELECT tgt_lang, COUNT(*) AS c FROM translation_sample " +
                        "WHERE customer_id=? AND store_id=? " + devFilter + "AND created_at>=? AND created_at<? " +
                        "GROUP BY tgt_lang",
                rs -> { langs.put(rs.getString("tgt_lang"), rs.getInt("c")); },
                withRange(scope, from, to));

        Integer qaHitCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qa_log WHERE customer_id=? AND store_id=? " + devFilter +
                        "AND status='ok' AND created_at>=? AND created_at<?",
                Integer.class, withRange(scope, from, to));

        String metrics;
        try {
            metrics = mapper.writeValueAsString(Map.of("langs", langs));
        } catch (JsonProcessingException e) {
            metrics = "{}";
        }
        return new DeviceAggregate(turnCount, sessionCount,
                qaHitCount == null ? 0 : qaHitCount, activeDays, metrics);
    }

    private static Object[] withRange(List<Object> scope, OffsetDateTime from, OffsetDateTime to) {
        Object[] args = new Object[scope.size() + 2];
        for (int i = 0; i < scope.size(); i++) {
            args[i] = scope.get(i);
        }
        args[scope.size()] = from;
        args[scope.size() + 1] = to;
        return args;
    }
}
