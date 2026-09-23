package com.aicard.ota.dto;

import java.util.List;

/** 有更新时的 update 对象：固定配套目标 + 有序包清单。 */
public record OtaUpdate(
        String updateId,
        String policy,
        TargetVersions targetVersions,
        List<OtaPackageDto> packages,
        String releaseNotes) {

    public record TargetVersions(String p4, String c5) {}
}
