package com.aicard.ota.service;

import com.aicard.ota.OtaException;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** 固件文件存取 + 短期签名 URL 生成/验证。文件由运维放到 storage-dir，登记元数据时云端复核。 */
public class OtaFileService {

    public record SignedUrl(String url, int expiresInSec) {}

    private static final String FILES_PATH = "/api/v1/ota/files/";

    private final Path storageDir;
    private final String signSecret;
    private final long urlExpiresSec;
    private final String publicBaseUrl;

    public OtaFileService(String storageDir, String signSecret, long urlExpiresSec, String publicBaseUrl) {
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.signSecret = signSecret;
        this.urlExpiresSec = urlExpiresSec;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
    }

    /** 生成带过期时间 + HMAC 签名的下载 URL；未配 public-base-url 时返回相对路径。 */
    public SignedUrl sign(String storageKey) {
        long expires = Instant.now().getEpochSecond() + urlExpiresSec;
        String path = FILES_PATH + storageKey;
        String relative = path + "?expires=" + expires + "&sig=" + hmac(path + "?expires=" + expires);
        String url = publicBaseUrl.isBlank() ? relative : publicBaseUrl + relative;
        return new SignedUrl(url, (int) urlExpiresSec);
    }

    /** 校验签名与有效期（常数时间比较）。 */
    public boolean verify(String storageKey, long expires, String sig) {
        if (sig == null || expires < Instant.now().getEpochSecond()) {
            return false;
        }
        String expected = hmac(FILES_PATH + storageKey + "?expires=" + expires);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析存储路径，防目录遍历。 */
    public Path resolve(String storageKey) {
        Path p = storageDir.resolve(storageKey).normalize();
        if (!p.startsWith(storageDir)) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "invalid storage key");
        }
        return p;
    }

    public byte[] read(String storageKey) {
        Path p = resolve(storageKey);
        if (!Files.isRegularFile(p)) {
            throw new OtaException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "firmware file not found");
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new OtaException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "failed to read firmware");
        }
    }

    public long fileSize(String storageKey) {
        Path p = resolve(storageKey);
        if (!Files.isRegularFile(p)) {
            throw new OtaException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "firmware file not found");
        }
        try {
            return Files.size(p);
        } catch (IOException e) {
            throw new OtaException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "failed to read firmware");
        }
    }

    public String sha256(String storageKey) {
        Path p = resolve(storageKey);
        if (!Files.isRegularFile(p)) {
            throw new OtaException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "firmware file not found");
        }
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new OtaException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "failed to read firmware");
        } catch (NoSuchAlgorithmException e) {
            throw new OtaException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "sha256 unavailable");
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new OtaException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "failed to sign url");
        }
    }
}
