package com.aicard.skb.model;

import java.time.Instant;

/** 知识库文档（按客户/门店隔离，domain 区分 skb 员工知识库 / vkb 游客知识库）。 */
public record Document(Long id, Long customerId, Long storeId, String title, String version,
                       String status, String domain, Instant createdAt) {}
