package com.aicard.skb.model;

import java.time.Instant;

/** 员工知识库文档（按客户/门店隔离，status=published 才可被检索）。 */
public record Document(Long id, Long customerId, Long storeId, String title, String version,
                       String status, Instant createdAt) {}
