package com.aicard.maintenance.dto;

/** 建维修单请求。 */
public record OrderRequest(String orderNo, String deviceId, String title, String description, String reporter) {}
