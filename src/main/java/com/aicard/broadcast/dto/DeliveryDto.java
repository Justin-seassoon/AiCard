package com.aicard.broadcast.dto;

import com.aicard.broadcast.domain.BroadcastDelivery;

import java.time.Instant;

/** 投递/回执状态（回执看板用）。 */
public record DeliveryDto(String deviceId, String translatedText, String targetLanguage,
                          Instant deliveredAt, Instant ackAt) {
    public static DeliveryDto from(BroadcastDelivery d) {
        return new DeliveryDto(d.getDeviceId(), d.getTranslatedText(), d.getTargetLanguage(),
                d.getDeliveredAt(), d.getAckAt());
    }
}
