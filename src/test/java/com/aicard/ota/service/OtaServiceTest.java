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
import com.aicard.ota.dto.OtaReleaseDto;
import com.aicard.ota.dto.OtaReleaseRequest;
import com.aicard.ota.repository.OtaPackageRepository;
import com.aicard.ota.repository.OtaReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtaServiceTest {

    @Mock OtaReleaseRepository releases;
    @Mock OtaPackageRepository packages;
    @Mock DeviceRepository devices;
    @Mock DeviceAuthenticator authenticator;
    @Mock OtaFileService files;

    private OtaService service;

    @BeforeEach
    void setUp() {
        service = new OtaService(releases, packages, devices, authenticator, files);
    }

    private Device device() {
        return Device.builder().id(1L).deviceId("d1").token("tok")
                .customerId(1L).storeId(1L).createdAt(Instant.now()).build();
    }

    private OtaRelease release(long id, String p4, String c5) {
        return OtaRelease.builder().id(id).updateId("r" + id).policy("optional")
                .targetP4Version(p4).targetC5Version(c5).status(OtaRelease.STATUS_ACTIVE)
                .createdAt(Instant.now()).build();
    }

    private OtaPackage pkg(String module, String version) {
        return OtaPackage.builder().module(module).targetVersion(version).imageSize(10L)
                .sha256("a".repeat(64)).storageKey(module + ".bin").sortOrder(0).build();
    }

    @Test
    void noActiveReleaseReturnsNoApplicable() {
        when(authenticator.authenticate("d1", "tok")).thenReturn(device());
        when(releases.findByStatus(OtaRelease.STATUS_ACTIVE)).thenReturn(Optional.empty());

        OtaCheckResponse resp = service.check(req("1.0", "1.0"), "tok");

        assertThat(resp.updateAvailable()).isFalse();
        assertThat(resp.reason()).isEqualTo("NO_APPLICABLE_RELEASE");
    }

    @Test
    void upToDateReturnsUpToDate() {
        when(authenticator.authenticate("d1", "tok")).thenReturn(device());
        when(releases.findByStatus(OtaRelease.STATUS_ACTIVE)).thenReturn(Optional.of(release(1L, "2.0", "1.5")));

        OtaCheckResponse resp = service.check(req("2.0", "1.5"), "tok");

        assertThat(resp.updateAvailable()).isFalse();
        assertThat(resp.reason()).isEqualTo("UP_TO_DATE");
    }

    @Test
    void returnsOnlyC5WhenP4UpToDate() {
        when(authenticator.authenticate("d1", "tok")).thenReturn(device());
        when(releases.findByStatus(OtaRelease.STATUS_ACTIVE)).thenReturn(Optional.of(release(1L, "2.0", "1.5")));
        when(packages.findByReleaseIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(pkg("p4", "2.0"), pkg("c5", "1.5")));
        when(files.sign("c5.bin")).thenReturn(new OtaFileService.SignedUrl("http://x/c5.bin?sig", 3600));

        OtaCheckResponse resp = service.check(req("2.0", "1.0"), "tok");

        assertThat(resp.updateAvailable()).isTrue();
        assertThat(resp.update().packages()).hasSize(1);
        assertThat(resp.update().packages().get(0).module()).isEqualTo("c5");
        assertThat(resp.update().targetVersions().p4()).isEqualTo("2.0");
    }

    @Test
    void returnsBothPackagesWhenBothBehind() {
        when(authenticator.authenticate("d1", "tok")).thenReturn(device());
        when(releases.findByStatus(OtaRelease.STATUS_ACTIVE)).thenReturn(Optional.of(release(1L, "2.0", "1.5")));
        when(packages.findByReleaseIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(pkg("p4", "2.0"), pkg("c5", "1.5")));
        when(files.sign(any())).thenReturn(new OtaFileService.SignedUrl("http://x/bin?sig", 3600));

        OtaCheckResponse resp = service.check(req("1.0", "1.0"), "tok");

        assertThat(resp.updateAvailable()).isTrue();
        assertThat(resp.update().packages()).hasSize(2);
    }

    @Test
    void invalidTokenRejected() {
        when(authenticator.authenticate("d1", "bad"))
                .thenThrow(new AuthenticationException("invalid token"));

        assertThatThrownBy(() -> service.check(req("1.0", "1.0"), "bad"))
                .isInstanceOf(OtaException.class);
    }

    @Test
    void publishRevokesOldActive() {
        OtaRelease old = release(1L, "1.0", "1.0");
        when(releases.findByStatus(OtaRelease.STATUS_ACTIVE)).thenReturn(Optional.of(old));
        when(releases.save(any(OtaRelease.class))).thenAnswer(inv -> inv.getArgument(0));
        when(packages.save(any(OtaPackage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(files.fileSize("p4.bin")).thenReturn(10L);
        when(files.sha256("p4.bin")).thenReturn("a".repeat(64));

        OtaReleaseRequest req = new OtaReleaseRequest("r2", "optional", "2.0", "1.5", null,
                List.of(new OtaReleaseRequest.Package("p4", "2.0", 10L, "a".repeat(64), "p4.bin")));

        service.publish(req);

        assertThat(old.getStatus()).isEqualTo(OtaRelease.STATUS_REVOKED);
    }

    @Test
    void publishRejectsShaMismatch() {
        when(files.fileSize("p4.bin")).thenReturn(10L);
        when(files.sha256("p4.bin")).thenReturn("b".repeat(64));

        OtaReleaseRequest req = new OtaReleaseRequest("r2", "optional", "2.0", "1.5", null,
                List.of(new OtaReleaseRequest.Package("p4", "2.0", 10L, "a".repeat(64), "p4.bin")));

        assertThatThrownBy(() -> service.publish(req)).isInstanceOf(OtaException.class);
    }

    @Test
    void revokeSetsStatus() {
        OtaRelease r = release(1L, "1.0", "1.0");
        when(releases.findById(1L)).thenReturn(Optional.of(r));
        when(releases.save(any(OtaRelease.class))).thenAnswer(inv -> inv.getArgument(0));
        when(packages.findByReleaseIdOrderBySortOrderAsc(1L)).thenReturn(List.of());

        OtaReleaseDto dto = service.revoke(1L);

        assertThat(dto.status()).isEqualTo(OtaRelease.STATUS_REVOKED);
    }

    private OtaCheckRequest req(String p4, String c5) {
        return new OtaCheckRequest("d1", null, null, new OtaCheckRequest.CurrentVersions(p4, c5), "boot");
    }
}
