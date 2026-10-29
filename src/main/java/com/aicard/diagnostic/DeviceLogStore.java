package com.aicard.diagnostic;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 设备诊断日志存取：文件按 {@code {storageDir}/{customerId}/{deviceId}/{logId}.log[.gz]} 落共享卷。
 * 路径全部由云端生成并做字符校验，不拼接用户输入。
 */
@Component
public class DeviceLogStore {

    private final Path root;
    private final DeviceLogConfig config;

    public DeviceLogStore(DeviceLogConfig config) {
        this.config = config;
        this.root = Path.of(config.getStorageDir()).toAbsolutePath().normalize();
    }

    public String write(Long customerId, String deviceId, String logId, boolean compressed, byte[] data) {
        String key = keyFor(customerId, deviceId, logId, compressed);
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("invalid device log path");
        }
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("device log write failed", e);
        }
    }

    public byte[] read(String storageKey) {
        Path p = root.resolve(storageKey).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("invalid storage key");
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException("device log read failed", e);
        }
    }

    public void delete(String storageKey) {
        Path p = root.resolve(storageKey).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("invalid storage key");
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException("device log delete failed", e);
        }
    }

    private static String keyFor(Long customerId, String deviceId, String logId, boolean compressed) {
        safe(deviceId);
        safe(logId);
        return customerId + "/" + deviceId + "/" + logId + (compressed ? ".log.gz" : ".log");
    }

    private static void safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid path segment");
        }
    }
}
