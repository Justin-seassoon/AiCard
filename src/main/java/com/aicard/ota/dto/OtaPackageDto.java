package com.aicard.ota.dto;

/** 单个待装固件包（协议 §3.1 packages[] 项）。 */
public record OtaPackageDto(
        String module,
        String targetVersion,
        Long imageSize,
        String sha256,
        String downloadUrl,
        Integer expiresInSec) {}
