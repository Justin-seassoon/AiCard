package com.aicard.skb.curation;

import com.aicard.skb.model.Chunk;
import com.aicard.skb.model.Document;
import com.aicard.skb.provider.EmbeddingProvider;
import com.aicard.skb.seed.KeywordExtractor;
import com.aicard.skb.store.KnowledgeStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 知识缺口后台接口（① 自生长知识）。租户经显式参数传入（demo 单租户，不依赖 TenantContext）。
 *
 * <p>流程：{@code /capture} 捕获 → 列表 → {@code /{id}/draft} 起草 → {@code /{id}/approve} 入库 / {@code /{id}/reject} 驳回。
 * <b>只有 approve 会写入 document/chunk</b>——人工审核是硬门，无自动入库路径。
 */
@RestController
@RequestMapping("/api/knowledge-gaps")
public class KnowledgeGapController {

    private static final int TITLE_MAX = 255;

    private final GapCaptureService capture;
    private final CandidateDraftService draft;
    private final GapStore gaps;
    private final KnowledgeStore store;
    private final EmbeddingProvider embeddings;
    private final KeywordExtractor keywordExtractor;

    public KnowledgeGapController(GapCaptureService capture, CandidateDraftService draft, GapStore gaps,
                                  KnowledgeStore store, EmbeddingProvider embeddings,
                                  KeywordExtractor keywordExtractor) {
        this.capture = capture;
        this.draft = draft;
        this.gaps = gaps;
        this.store = store;
        this.embeddings = embeddings;
        this.keywordExtractor = keywordExtractor;
    }

    @PostMapping("/capture")
    public int capture(@RequestParam(defaultValue = "1") Long customerId,
                       @RequestParam(defaultValue = "1") Long storeId,
                       @RequestParam(defaultValue = "skb") String domain) {
        return capture.capture(customerId, storeId, domain);
    }

    @GetMapping
    public List<KnowledgeGap> list(@RequestParam(defaultValue = "1") Long customerId,
                                   @RequestParam(defaultValue = "1") Long storeId,
                                   @RequestParam(defaultValue = "skb") String domain,
                                   @RequestParam(required = false) String status) {
        return gaps.list(customerId, storeId, domain, status);
    }

    @PostMapping("/{id}/draft")
    public KnowledgeGap draft(@PathVariable Long id,
                              @RequestParam(defaultValue = "1") Long customerId,
                              @RequestParam(defaultValue = "1") Long storeId,
                              @RequestParam(defaultValue = "skb") String domain) {
        return draft.draft(id, customerId, storeId, domain);
    }

    /** 审核通过 → 入库（document=published + chunk）；answer 缺省用草稿。 */
    @PostMapping("/{id}/approve")
    public void approve(@PathVariable Long id, @RequestBody(required = false) ApproveRequest req,
                        @RequestParam(defaultValue = "1") Long customerId,
                        @RequestParam(defaultValue = "1") Long storeId,
                        @RequestParam(defaultValue = "skb") String domain) {
        KnowledgeGap gap = gaps.find(id, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "gap not found"));
        String answer = req != null && req.answer() != null && !req.answer().isBlank()
                ? req.answer() : gap.draftAnswer();
        if (answer == null || answer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer required");
        }

        Document doc = store.insertDocument(new Document(null, customerId, storeId,
                titleOf(gap.question()), "v1", "published", domain, Instant.now()));
        String text = "Q: " + gap.question() + " A: " + answer;
        store.insertChunk(new Chunk(null, doc.id(), customerId, storeId, 0, text),
                embeddings.embed(gap.question()),
                embeddings.embed(answer),
                keywordExtractor.extract(gap.question()));
        gaps.markApproved(id, customerId, storeId, doc.id());
    }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable Long id,
                       @RequestParam(defaultValue = "1") Long customerId,
                       @RequestParam(defaultValue = "1") Long storeId) {
        gaps.markRejected(id, customerId, storeId);
    }

    private static String titleOf(String question) {
        return question.length() <= TITLE_MAX ? question : question.substring(0, TITLE_MAX);
    }

    public record ApproveRequest(String answer) {}
}
