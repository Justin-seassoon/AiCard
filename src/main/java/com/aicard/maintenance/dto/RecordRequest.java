package com.aicard.maintenance.dto;

/** 提交点检记录请求。 */
public record RecordRequest(String deviceId, String results, String inspectedBy, String note) {}
