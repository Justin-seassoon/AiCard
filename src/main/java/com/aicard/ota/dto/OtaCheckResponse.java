package com.aicard.ota.dto;

/** 检查更新响应：无更新时 update 为 null，reason 说明原因。 */
public record OtaCheckResponse(boolean updateAvailable, OtaUpdate update, String reason) {}
