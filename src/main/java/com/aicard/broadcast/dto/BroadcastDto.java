package com.aicard.broadcast.dto;

import com.aicard.broadcast.domain.Broadcast;

import java.time.Instant;

public record BroadcastDto(Long id, Long groupId, String sourceText, String sourceLanguage, Instant createdAt) {
    public static BroadcastDto from(Broadcast b) {
        return new BroadcastDto(b.getId(), b.getGroupId(), b.getSourceText(), b.getSourceLanguage(), b.getCreatedAt());
    }
}
