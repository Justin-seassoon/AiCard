package com.aicard.ota.dto;

import com.aicard.ota.domain.OtaPackage;
import com.aicard.ota.domain.OtaRelease;

import java.time.Instant;
import java.util.List;

/** 发布/列表/撤销响应。 */
public record OtaReleaseDto(
        Long id,
        String updateId,
        String policy,
        String targetP4Version,
        String targetC5Version,
        String releaseNotes,
        String status,
        Instant createdAt,
        List<Package> packages) {

    public record Package(String module, String targetVersion, Long imageSize, String sha256,
                          String storageKey, Integer sortOrder) {}

    public static OtaReleaseDto from(OtaRelease r, List<OtaPackage> packages) {
        return new OtaReleaseDto(
                r.getId(), r.getUpdateId(), r.getPolicy(), r.getTargetP4Version(), r.getTargetC5Version(),
                r.getReleaseNotes(), r.getStatus(), r.getCreatedAt(),
                packages.stream().map(p -> new Package(p.getModule(), p.getTargetVersion(), p.getImageSize(),
                        p.getSha256(), p.getStorageKey(), p.getSortOrder())).toList());
    }
}
