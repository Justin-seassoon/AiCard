package com.aicard.skb.web;

import com.aicard.skb.model.SkbResult;
import com.aicard.skb.service.SkbService;
import com.aicard.skb.store.KnowledgeStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库测试接口：供测试页调用。
 * answer —— 输入日语问题返回检索答案；documents —— 返回全部知识库内容（按主题分组的问答）。
 */
@RestController
public class SkbController {

    private final SkbService service;
    private final KnowledgeStore store;

    public SkbController(SkbService service, KnowledgeStore store) {
        this.service = service;
        this.store = store;
    }

    @GetMapping("/api/skb/answer")
    public SkbResult answer(@RequestParam String question,
                            @RequestParam(defaultValue = "1") Long customerId,
                            @RequestParam(defaultValue = "1") Long storeId,
                            @RequestParam(defaultValue = "skb") String domain) {
        return service.answer(question, customerId, storeId, domain);
    }

    @GetMapping("/api/skb/documents")
    public List<DocumentView> documents(@RequestParam(defaultValue = "1") Long customerId,
                                        @RequestParam(defaultValue = "1") Long storeId,
                                        @RequestParam(defaultValue = "skb") String domain) {
        return store.listDocuments(customerId, storeId, domain).stream()
                .map(doc -> new DocumentView(doc.title(),
                        store.listChunks(doc.id()).stream()
                                .map(c -> parseQa(c.text()))
                                .toList()))
                .toList();
    }

    /** chunk.text 形如 "Q: 问题 A: 答案"，拆成问答。 */
    private static QaView parseQa(String text) {
        int idx = text.indexOf(" A: ");
        if (text.startsWith("Q: ") && idx > 3) {
            return new QaView(text.substring(3, idx), text.substring(idx + 4));
        }
        return new QaView("", text);
    }

    public record DocumentView(String title, List<QaView> qas) {}
    public record QaView(String question, String answer) {}
}
