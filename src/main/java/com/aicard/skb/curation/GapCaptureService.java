package com.aicard.skb.curation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺口捕获（① 自生长知识）：把 {@code qa_log(status='no_match')} 的问句**归一化 → 聚合 → upsert** 进缺口池。
 *
 * <p><b>幂等</b>：每次都对当前全部 no_match 记录**重算计数**（非增量、无水位线），重复执行安全。
 * 聚合键 = 归一化问句的 SHA-256；同一问法的代表问法取「最早见到」那一条。
 */
@Service
public class GapCaptureService {

    private final JdbcTemplate jdbc;
    private final GapStore gaps;

    public GapCaptureService(JdbcTemplate jdbc, GapStore gaps) {
        this.jdbc = jdbc;
        this.gaps = gaps;
    }

    /** 读取该租户 + domain 下全部 no_match 问答，聚合成缺口并 upsert；返回涉及的缺口条数。 */
    public int capture(long customerId, long storeId, String domain) {
        List<Row> rows = jdbc.query(
                "SELECT question, created_at FROM qa_log " +
                        "WHERE customer_id=? AND store_id=? AND source=? AND status='no_match' AND question IS NOT NULL",
                (rs, i) -> new Row(rs.getString("question"), rs.getTimestamp("created_at").toInstant()),
                customerId, storeId, domain);
        List<Agg> aggs = aggregate(rows);
        for (Agg a : aggs) {
            gaps.upsert(customerId, storeId, domain, a.question(), a.key(), a.occurrences(), a.firstSeen());
        }
        return aggs.size();
    }

    public record Row(String question, Instant at) {}

    public record Agg(String question, String key, int occurrences, Instant firstSeen) {}

    /** 纯函数：按归一化指纹分组合并，代表问法取最早者，结果按出现次数降序。 */
    static List<Agg> aggregate(List<Row> rows) {
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row r : rows) {
            String normalized = normalize(r.question());
            if (normalized.isBlank()) {
                continue;
            }
            byKey.computeIfAbsent(key(normalized), k -> new ArrayList<>()).add(r);
        }
        List<Agg> out = new ArrayList<>(byKey.size());
        for (Map.Entry<String, List<Row>> e : byKey.entrySet()) {
            List<Row> group = e.getValue();
            Row earliest = group.stream().min(Comparator.comparing(Row::at)).orElse(group.get(0));
            out.add(new Agg(earliest.question(), e.getKey(), group.size(), earliest.at()));
        }
        out.sort(Comparator.comparingInt(Agg::occurrences).reversed());
        return out;
    }

    /** 归一化：去空白（含全角空格）→ 去标点符号 → 小写。用于精确去重的指纹计算。 */
    static String normalize(String q) {
        if (q == null) {
            return "";
        }
        return q.replaceAll("[\\s\\u3000]+", "")
                .replaceAll("[、。．，！？!?・「」『』（）()\\[\\]【】｛｝{}:：;；\"'’“”]+", "")
                .toLowerCase();
    }

    static String key(String normalized) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
