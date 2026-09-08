package com.aicard.skb.store;

import com.aicard.skb.model.Chunk;
import com.aicard.skb.model.Document;
import com.aicard.skb.model.RetrievedChunk;
import com.pgvector.PGvector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 知识库向量存储：pgvector 余弦检索。检索强制带 customer_id + store_id + domain（四层隔离 + SKB/VKB 隔离），
 * 绝不跨租户、绝不跨域；仅命中 status='published' 的文档切片。created_at 由数据库默认填充。
 */
@Repository
public class KnowledgeStore {

    private final JdbcTemplate jdbc;

    public KnowledgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Document insertDocument(Document d) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO document(customer_id, store_id, title, version, status, domain) VALUES (?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, d.customerId());
            ps.setLong(2, d.storeId());
            ps.setString(3, d.title());
            ps.setString(4, d.version());
            ps.setString(5, d.status());
            ps.setString(6, d.domain());
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        return new Document(id, d.customerId(), d.storeId(), d.title(), d.version(), d.status(),
                d.domain(), d.createdAt());
    }

    public void insertChunk(Chunk c, List<Float> embedding) {
        PGvector vec = new PGvector(embedding);
        jdbc.update(
                "INSERT INTO chunk(document_id, customer_id, store_id, chunk_index, text, embedding) VALUES (?,?,?,?,?,?)",
                c.documentId(), c.customerId(), c.storeId(), c.chunkIndex(), c.text(), vec);
    }

    public List<RetrievedChunk> searchSimilar(Long customerId, Long storeId, String domain,
                                              List<Float> embedding, int topK) {
        PGvector vec = new PGvector(embedding);
        String sql = """
                SELECT c.id, c.text, d.title, d.version,
                       1 - (c.embedding <=> ?::vector) AS similarity
                FROM chunk c JOIN document d ON c.document_id = d.id
                WHERE c.customer_id = ? AND c.store_id = ? AND d.status = 'published' AND d.domain = ?
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """;
        RowMapper<RetrievedChunk> mapper = (rs, i) -> new RetrievedChunk(
                rs.getLong("id"), rs.getString("text"), rs.getString("title"),
                rs.getString("version"), rs.getDouble("similarity"));
        return jdbc.query(sql, mapper, vec, customerId, storeId, domain, vec, topK);
    }

    public List<Document> listDocuments(Long customerId, Long storeId, String domain) {
        String sql = """
                SELECT id, customer_id, store_id, title, version, status, domain
                FROM document WHERE customer_id = ? AND store_id = ? AND status = 'published' AND domain = ?
                ORDER BY id
                """;
        return jdbc.query(sql, (rs, i) -> new Document(
                rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("store_id"),
                rs.getString("title"), rs.getString("version"), rs.getString("status"),
                rs.getString("domain"), null), customerId, storeId, domain);
    }

    public List<Chunk> listChunks(Long documentId) {
        String sql = """
                SELECT id, document_id, customer_id, store_id, chunk_index, text
                FROM chunk WHERE document_id = ?
                ORDER BY chunk_index
                """;
        return jdbc.query(sql, (rs, i) -> new Chunk(
                rs.getLong("id"), rs.getLong("document_id"), rs.getLong("customer_id"),
                rs.getLong("store_id"), rs.getInt("chunk_index"), rs.getString("text")), documentId);
    }

    public void publishDocument(Long id) {
        jdbc.update("UPDATE document SET status = 'published' WHERE id = ?", id);
    }
}
