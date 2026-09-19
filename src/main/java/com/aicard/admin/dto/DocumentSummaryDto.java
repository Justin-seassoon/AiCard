package com.aicard.admin.dto;

import com.aicard.skb.model.Document;

import java.time.Instant;

/** 知识库文档摘要（含 id/status，供后台列表 + 发布用）。 */
public record DocumentSummaryDto(Long id, String title, String version, String status,
                                 String domain, Instant createdAt) {
    public static DocumentSummaryDto from(Document d) {
        return new DocumentSummaryDto(d.id(), d.title(), d.version(), d.status(), d.domain(), d.createdAt());
    }
}
