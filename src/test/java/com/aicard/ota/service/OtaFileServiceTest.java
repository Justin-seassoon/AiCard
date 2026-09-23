package com.aicard.ota.service;

import com.aicard.ota.OtaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtaFileServiceTest {

    @TempDir
    Path tmpDir;

    private OtaFileService service() {
        return new OtaFileService(tmpDir.toString(), "secret", 3600, "");
    }

    @Test
    void signAndVerifyRoundTrip() throws Exception {
        Files.writeString(tmpDir.resolve("p4.bin"), "hello");
        OtaFileService service = service();
        OtaFileService.SignedUrl signed = service.sign("p4.bin");

        long expires = Long.parseLong(extract(signed.url(), "expires"));
        String sig = extract(signed.url(), "sig");
        assertThat(service.verify("p4.bin", expires, sig)).isTrue();
    }

    @Test
    void expiredUrlIsRejected() {
        assertThat(service().verify("p4.bin", 1L, "deadbeef")).isFalse();
    }

    @Test
    void tamperedSigIsRejected() throws Exception {
        Files.writeString(tmpDir.resolve("p4.bin"), "hello");
        OtaFileService service = service();
        OtaFileService.SignedUrl signed = service.sign("p4.bin");

        long expires = Long.parseLong(extract(signed.url(), "expires"));
        assertThat(service.verify("p4.bin", expires, "0000")).isFalse();
    }

    @Test
    void computesSha256AndSize() throws Exception {
        Files.writeString(tmpDir.resolve("c5.bin"), "abc");
        OtaFileService service = service();
        assertThat(service.fileSize("c5.bin")).isEqualTo(3);
        assertThat(service.sha256("c5.bin"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> service().resolve("../secret.bin")).isInstanceOf(OtaException.class);
    }

    private String extract(String url, String key) {
        for (String part : url.split("[?&]")) {
            if (part.startsWith(key + "=")) {
                return part.substring(key.length() + 1);
            }
        }
        throw new IllegalArgumentException("key not found: " + key);
    }
}
