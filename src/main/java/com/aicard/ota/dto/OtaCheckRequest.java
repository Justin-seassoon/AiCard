package com.aicard.ota.dto;

/** 设备检查更新请求（协议 §3.1，字段 snake_case 由全局 Jackson 策略映射）。 */
public record OtaCheckRequest(
        String deviceId,
        String productModel,
        String hardwareVersion,
        CurrentVersions currentVersions,
        String trigger) {

    public record CurrentVersions(String p4, String c5) {}
}
