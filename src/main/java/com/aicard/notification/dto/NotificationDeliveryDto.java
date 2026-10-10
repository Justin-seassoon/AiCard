package com.aicard.notification.dto;

import com.aicard.notification.domain.NotificationDelivery;

import java.time.Instant;

public record NotificationDeliveryDto(Long id, Long notificationId, String deviceId,
                                      String translatedText, String targetLanguage, Instant deliveredAt) {
    public static NotificationDeliveryDto from(NotificationDelivery d) {
        return new NotificationDeliveryDto(d.getId(), d.getNotificationId(), d.getDeviceId(),
                d.getTranslatedText(), d.getTargetLanguage(), d.getDeliveredAt());
    }
}
