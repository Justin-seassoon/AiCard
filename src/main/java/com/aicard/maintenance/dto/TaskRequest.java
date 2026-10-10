package com.aicard.maintenance.dto;

/** 建点检任务请求。 */
public record TaskRequest(String taskNo, String deviceId, String title, String checklist,
                          java.time.LocalDate scheduledDate, String assignee) {}
