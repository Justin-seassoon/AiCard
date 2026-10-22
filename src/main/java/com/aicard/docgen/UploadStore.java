package com.aicard.docgen;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 上传文件解析结果的存储（租户隔离；只存结构化 JSON，不存原始文件）。 */
@Repository
public class UploadStore {

    private final JdbcTemplate jdbc;

    public UploadStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long save(long customerId, long storeId, String filename, String mime,
                     String parsedJson, String warningsJson) {
        Long id = jdbc.queryForObject(
                "INSERT INTO doc_upload(customer_id, store_id, filename, mime, parsed, warnings) " +
                        "VALUES (?,?,?,?,?::jsonb,?::jsonb) RETURNING id",
                Long.class,
                customerId, storeId, filename, mime, parsedJson, warningsJson);
        return id == null ? -1 : id;
    }

    public Optional<StoredUpload> find(long id, long customerId, long storeId) {
        return jdbc.query(
                "SELECT id, filename, mime, parsed::text AS parsed, warnings::text AS warnings, created_at " +
                        "FROM doc_upload WHERE id=? AND customer_id=? AND store_id=?",
                (rs, i) -> new StoredUpload(rs.getLong("id"), rs.getString("filename"), rs.getString("mime"),
                        rs.getString("parsed"), rs.getString("warnings"),
                        rs.getTimestamp("created_at").toInstant()),
                id, customerId, storeId).stream().findFirst();
    }

    public List<StoredUpload> list(long customerId, long storeId, int limit) {
        return jdbc.query(
                "SELECT id, filename, mime, parsed::text AS parsed, warnings::text AS warnings, created_at " +
                        "FROM doc_upload WHERE customer_id=? AND store_id=? ORDER BY id DESC LIMIT ?",
                (rs, i) -> new StoredUpload(rs.getLong("id"), rs.getString("filename"), rs.getString("mime"),
                        null, null, rs.getTimestamp("created_at").toInstant()),
                customerId, storeId, limit);
    }

    public record StoredUpload(Long id, String filename, String mime, String parsed, String warnings,
                               Instant createdAt) {}
}
