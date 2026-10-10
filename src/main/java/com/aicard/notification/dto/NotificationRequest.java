package com.aicard.notification.dto;

import java.time.Instant;

/** 建运营通知请求。 */
public record NotificationRequest(String title, String content, String sourceLanguage,
                                  String targetType, String targetId, String triggerType,
                                  Instant scheduleAt, String cronExpr) {}
