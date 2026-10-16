package com.aicard.skb.curation;

import java.time.Instant;

/**
 * 知识缺口候选（自生长知识 ①）：来自 {@code qa_log(status='no_match')} 的聚合，
 * 经 LLM 起草 + **人工审核**后才入库（{@code document}/{@code chunk}）。
 * 状态流转：{@code open → drafted → approved | rejected}。
 */
public record KnowledgeGap(
        Long id,
        Long customerId,
        Long storeId,
        String domain,
        String question,
        int occurrences,
        String status,
        String draftAnswer,
        String draftRefs,
        Long resolvedDocumentId,
        Instant firstSeenAt,
        Instant lastSeenAt) {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_DRAFTED = "drafted";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
}
