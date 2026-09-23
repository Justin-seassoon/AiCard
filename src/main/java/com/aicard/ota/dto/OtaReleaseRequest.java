package com.aicard.ota.dto;

import java.util.List;

/** 后台发布请求：登记固定配套元数据（固件由运维放目录，云端复核 sha256/size）。 */
public record OtaReleaseRequest(
        String updateId,
        String policy,
        String targetP4Version,
        String targetC5Version,
        String releaseNotes,
        List<Package> packages) {

    public record Package(String module, String targetVersion, Long imageSize, String sha256, String storageKey) {}
}
