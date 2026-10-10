package com.aicard.notification.dto;

import com.aicard.notification.domain.OperationNotification;

import java.time.Instant;

public record NotificationDto(Long id, String title, String content, String sourceLanguage,
                              String targetType, String targetId, String triggerType,
                              Instant scheduleAt, String cronExpr, String status, Instant createdAt) {
    public static NotificationDto from(OperationNotification n) {
        return new NotificationDto(n.getId(), n.getTitle(), n.getContent(), n.getSourceLanguage(),
                n.getTargetType(), n.getTargetId(), n.getTriggerType(), n.getScheduleAt(),
                n.getCronExpr(), n.getStatus(), n.getCreatedAt());
    }
}
