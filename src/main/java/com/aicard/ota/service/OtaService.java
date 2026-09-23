package com.aicard.ota.service;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.gateway.auth.AuthenticationException;
import com.aicard.gateway.auth.DeviceAuthenticator;
import com.aicard.ota.OtaException;
import com.aicard.ota.domain.OtaPackage;
import com.aicard.ota.domain.OtaRelease;
import com.aicard.ota.dto.OtaCheckRequest;
import com.aicard.ota.dto.OtaCheckResponse;
import com.aicard.ota.dto.OtaPackageDto;
import com.aicard.ota.dto.OtaReleaseDto;
import com.aicard.ota.dto.OtaReleaseRequest;
import com.aicard.ota.dto.OtaUpdate;
import com.aicard.ota.repository.OtaPackageRepository;
import com.aicard.ota.repository.OtaReleaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * OTA 编排：固定配套发布/撤销 + 检查更新。
 * 单 active release 语义：发布新 release 时旧 active 自动撤销；check 只服务当前 active。
 */
@Service
public class OtaService {

    private static final Set<String> MODULES = Set.of("p4", "c5");
    private static final Set<String> POLICIES = Set.of("optional", "mandatory");

    private final OtaReleaseRepository releases;
    private final OtaPackageRepository packages;
    private final DeviceRepository devices;
    private final DeviceAuthenticator authenticator;
    private final OtaFileService files;

    public OtaService(OtaReleaseRepository releases, OtaPackageRepository packages,
                      DeviceRepository devices, DeviceAuthenticator authenticator, OtaFileService files) {
        this.releases = releases;
        this.packages = packages;
        this.devices = devices;
        this.authenticator = authenticator;
        this.files = files;
    }

    @Transactional
    public OtaReleaseDto publish(OtaReleaseRequest req) {
        validate(req);

        // 发布前复核每个固件文件的 size + sha256，防运维放错文件
        Map<String, String> targetByModule = Map.of("p4", req.targetP4Version(), "c5", req.targetC5Version());
        Set<String> seen = new HashSet<>();
        for (OtaReleaseRequest.Package p : req.packages()) {
            if (!MODULES.contains(p.module())) {
                throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "unknown module: " + p.module());
            }
            if (!seen.add(p.module())) {
                throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "duplicate module: " + p.module());
            }
            if (!Objects.equals(targetByModule.get(p.module()), p.targetVersion())) {
                throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                        "package target_version must equal target_" + p.module() + "_version");
            }
            long actualSize = files.fileSize(p.storageKey());
            if (actualSize != p.imageSize()) {
                throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                        "image_size mismatch for " + p.module() + ": registered " + p.imageSize() + ", actual " + actualSize);
            }
            String actualSha = files.sha256(p.storageKey());
            if (!actualSha.equalsIgnoreCase(p.sha256())) {
                throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                        "sha256 mismatch for " + p.module());
            }
        }

        // 旧 active 自动撤销
        releases.findByStatus(OtaRelease.STATUS_ACTIVE).ifPresent(r -> {
            r.setStatus(OtaRelease.STATUS_REVOKED);
            releases.save(r);
        });

        OtaRelease release = releases.save(OtaRelease.builder()
                .updateId(req.updateId())
                .policy(req.policy())
                .targetP4Version(req.targetP4Version())
                .targetC5Version(req.targetC5Version())
                .releaseNotes(req.releaseNotes())
                .status(OtaRelease.STATUS_ACTIVE)
                .createdAt(Instant.now())
                .build());

        int order = 0;
        List<OtaPackage> saved = new ArrayList<>();
        for (OtaReleaseRequest.Package p : req.packages()) {
            saved.add(packages.save(OtaPackage.builder()
                    .releaseId(release.getId())
                    .module(p.module())
                    .targetVersion(p.targetVersion())
                    .imageSize(p.imageSize())
                    .sha256(p.sha256().toLowerCase())
                    .storageKey(p.storageKey())
                    .sortOrder(order++)
                    .build()));
        }
        return OtaReleaseDto.from(release, saved);
    }

    @Transactional(readOnly = true)
    public List<OtaReleaseDto> list() {
        return releases.findAllByOrderByCreatedAtDesc().stream()
                .map(r -> OtaReleaseDto.from(r, packages.findByReleaseIdOrderBySortOrderAsc(r.getId())))
                .toList();
    }

    @Transactional
    public OtaReleaseDto revoke(Long id) {
        OtaRelease r = releases.findById(id)
                .orElseThrow(() -> new OtaException(HttpStatus.NOT_FOUND, "NOT_FOUND", "release not found"));
        r.setStatus(OtaRelease.STATUS_REVOKED);
        releases.save(r);
        return OtaReleaseDto.from(r, packages.findByReleaseIdOrderBySortOrderAsc(r.getId()));
    }

    @Transactional
    public OtaCheckResponse check(OtaCheckRequest req, String token) {
        Device device = authenticate(req.deviceId(), token);
        verifyProfile(device, req);
        recordVersions(device, req);

        OtaRelease release = releases.findByStatus(OtaRelease.STATUS_ACTIVE).orElse(null);
        if (release == null) {
            return new OtaCheckResponse(false, null, "NO_APPLICABLE_RELEASE");
        }

        String curP4 = req.currentVersions() == null ? null : req.currentVersions().p4();
        String curC5 = req.currentVersions() == null ? null : req.currentVersions().c5();
        boolean p4UpToDate = Objects.equals(curP4, release.getTargetP4Version());
        boolean c5UpToDate = Objects.equals(curC5, release.getTargetC5Version());
        if (p4UpToDate && c5UpToDate) {
            return new OtaCheckResponse(false, null, "UP_TO_DATE");
        }

        List<OtaPackageDto> need = new ArrayList<>();
        for (OtaPackage p : packages.findByReleaseIdOrderBySortOrderAsc(release.getId())) {
            boolean upToDate = "p4".equals(p.getModule()) ? p4UpToDate : c5UpToDate;
            if (!upToDate) {
                OtaFileService.SignedUrl signed = files.sign(p.getStorageKey());
                need.add(new OtaPackageDto(p.getModule(), p.getTargetVersion(), p.getImageSize(),
                        p.getSha256(), signed.url(), signed.expiresInSec()));
            }
        }

        OtaUpdate update = new OtaUpdate(release.getUpdateId(), release.getPolicy(),
                new OtaUpdate.TargetVersions(release.getTargetP4Version(), release.getTargetC5Version()),
                need, release.getReleaseNotes());
        return new OtaCheckResponse(true, update, null);
    }

    private Device authenticate(String deviceId, String token) {
        try {
            return authenticator.authenticate(deviceId, token);
        } catch (AuthenticationException | IllegalArgumentException e) {
            throw new OtaException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "device credential invalid");
        }
    }

    private void verifyProfile(Device device, OtaCheckRequest req) {
        if (device.getProductModel() != null && !device.getProductModel().isBlank()
                && !device.getProductModel().equals(req.productModel())) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "product_model mismatch");
        }
        if (device.getHardwareVersion() != null && !device.getHardwareVersion().isBlank()
                && !device.getHardwareVersion().equals(req.hardwareVersion())) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "hardware_version mismatch");
        }
    }

    private void recordVersions(Device device, OtaCheckRequest req) {
        if (req.currentVersions() == null) {
            return;
        }
        device.setLastP4Version(req.currentVersions().p4());
        device.setLastC5Version(req.currentVersions().c5());
        devices.save(device);
    }

    private void validate(OtaReleaseRequest req) {
        if (req.updateId() == null || req.updateId().isBlank()) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "update_id is required");
        }
        if (!POLICIES.contains(req.policy())) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "invalid policy");
        }
        if (req.targetP4Version() == null || req.targetP4Version().isBlank()
                || req.targetC5Version() == null || req.targetC5Version().isBlank()) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "target versions are required");
        }
        if (req.packages() == null || req.packages().isEmpty() || req.packages().size() > 2) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "packages must be 1-2 items");
        }
    }
}
