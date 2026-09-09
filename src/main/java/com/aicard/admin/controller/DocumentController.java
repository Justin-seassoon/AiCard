package com.aicard.admin.controller;

import com.aicard.admin.dto.DocumentImportRequest;
import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.skb.model.Chunk;
import com.aicard.skb.model.Document;
import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.seed.KeywordExtractor;
import com.aicard.skb.store.KnowledgeStore;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 后台知识库管理：导入文档（draft）+ 发布。embedding 由后端生成，前端只传文本。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final KnowledgeStore store;
    private final EmbeddingProvider embeddings;
    private final KeywordExtractor keywordExtractor;

    public DocumentController(KnowledgeStore store, EmbeddingProvider embeddings, KeywordExtractor keywordExtractor) {
        this.store = store;
        this.embeddings = embeddings;
        this.keywordExtractor = keywordExtractor;
    }

    @PostMapping
    public Long importDocument(@RequestBody DocumentImportRequest req) {
        Tenant t = TenantContext.require();
        String domain = req.domain() == null || req.domain().isBlank() ? "skb" : req.domain();
        Document doc = store.insertDocument(new Document(null, t.customerId(), t.storeId(),
                req.title(), req.version(), "draft", domain, Instant.now()));
        int i = 0;
        for (DocumentImportRequest.ChunkIn c : req.chunks()) {
            store.insertChunk(new Chunk(null, doc.id(), t.customerId(), t.storeId(), i++, c.text()),
                    embeddings.embed(c.text()),
                    embeddings.embed(c.text()),
                    keywordExtractor.extract(c.text()));
        }
        return doc.id();
    }

    @PostMapping("/{id}/publish")
    public void publish(@PathVariable Long id) {
        store.publishDocument(id);
    }
}
