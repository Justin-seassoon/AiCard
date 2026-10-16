package com.aicard.skb.curation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 知识缺口仓储（JdbcTemplate）。所有读/写均按 customer_id + store_id 限定，绝不跨租户。
 * 捕获 upsert 只更新计数与 last_seen，**不覆盖**已审核的 status 与代表性 question。
 */
@Repository
public class GapStore {

    private static final RowMapper<KnowledgeGap> MAPPER = (rs, i) -> new KnowledgeGap(
            rs.getLong("id"),
            rs.getLong("customer_id"),
            rs.getLong("store_id"),
            rs.getString("domain"),
            rs.getString("question"),
            rs.getInt("occurrences"),
            rs.getString("status"),
            rs.getString("draft_answer"),
            rs.getString("draft_refs"),
            (Long) rs.getObject("resolved_document_id"),
            rs.getTimestamp("first_seen_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant());

    private final JdbcTemplate jdbc;

    public GapStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 捕获 upsert：更新 occurrences/last_seen；冲突时不改 question/status（保留代表性问法与审核结果）。 */
    public void upsert(long customerId, long storeId, String domain, String question, String key,
                       int occurrences, Instant firstSeen) {
        jdbc.update("""
                INSERT INTO knowledge_gap(customer_id, store_id, domain, question, question_key,
                                          occurrences, first_seen_at, last_seen_at)
                VALUES (?,?,?,?,?,?,?, now())
                ON CONFLICT (customer_id, store_id, domain, question_key)
                DO UPDATE SET occurrences = EXCLUDED.occurrences, last_seen_at = now()
                """,
                customerId, storeId, domain, question, key, occurrences, Timestamp.from(firstSeen));
    }

    public List<KnowledgeGap> list(long customerId, long storeId, String domain, String status) {
        String sql = "SELECT * FROM knowledge_gap WHERE customer_id=? AND store_id=? AND domain=?";
        if (status == null || status.isBlank()) {
            return jdbc.query(sql + " ORDER BY occurrences DESC, id", MAPPER, customerId, storeId, domain);
        }
        return jdbc.query(sql + " AND status=? ORDER BY occurrences DESC, id",
                MAPPER, customerId, storeId, domain, status);
    }

    public Optional<KnowledgeGap> find(long id, long customerId, long storeId) {
        return jdbc.query("SELECT * FROM knowledge_gap WHERE id=? AND customer_id=? AND store_id=?",
                MAPPER, id, customerId, storeId).stream().findFirst();
    }

    public void updateDraft(long id, long customerId, long storeId, String draftAnswer, String draftRefs) {
        jdbc.update("UPDATE knowledge_gap SET draft_answer=?, draft_refs=?, status='drafted', last_seen_at=now() " +
                        "WHERE id=? AND customer_id=? AND store_id=?",
                draftAnswer, draftRefs, id, customerId, storeId);
    }

    public void markApproved(long id, long customerId, long storeId, long documentId) {
        jdbc.update("UPDATE knowledge_gap SET status='approved', resolved_document_id=? " +
                        "WHERE id=? AND customer_id=? AND store_id=?",
                documentId, id, customerId, storeId);
    }

    public void markRejected(long id, long customerId, long storeId) {
        jdbc.update("UPDATE knowledge_gap SET status='rejected' WHERE id=? AND customer_id=? AND store_id=?",
                id, customerId, storeId);
    }
}
