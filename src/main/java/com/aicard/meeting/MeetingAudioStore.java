package com.aicard.meeting;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * 会议音频存取：文件按 {@code {storageDir}/{customerId}/{meetingId}/{seq}.{codec}} 落共享卷。
 * <p>只做确定性文件操作；路径全部由云端生成并做字符校验，不拼接用户输入。</p>
 */
@Component
public class MeetingAudioStore {

    private final Path root;
    private final MeetingConfig config;

    public MeetingAudioStore(MeetingConfig config) {
        this.config = config;
        this.root = Path.of(config.getStorageDir()).toAbsolutePath().normalize();
    }

    public String write(Long customerId, String meetingId, int seq, String codec, byte[] data) {
        Path target = pathFor(customerId, meetingId, seq, codec);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return keyFor(customerId, meetingId, seq, codec);
        } catch (IOException e) {
            throw new UncheckedIOException("meeting audio write failed", e);
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
            throw new UncheckedIOException("meeting audio read failed", e);
        }
    }

    /**
     * 拼接全部分片。PCM 16k 单声道 → 加 WAV 头（浏览器可直接播放）；
     * 其他编码原样返回（Opus 需容器，P0 仅模拟器使用 PCM）。
     */
    public byte[] concat(Meeting meeting, List<MeetingChunk> chunks, int sampleRate) {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        for (MeetingChunk c : chunks) {
            audio.writeBytes(read(c.getStorageKey()));
        }
        byte[] pcm = audio.toByteArray();
        if (!"pcm16".equalsIgnoreCase(meeting.getCodec())) {
            return pcm;
        }
        return withWavHeader(pcm, sampleRate);
    }

    public void deleteMeeting(Long customerId, String meetingId) {
        Path dir = directoryFor(customerId, meetingId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("meeting audio delete failed", e);
        }
    }

    public String keyFor(Long customerId, String meetingId, int seq, String codec) {
        return customerId + "/" + meetingId + "/" + String.format(Locale.ROOT, "%08d", seq)
                + "." + extension(codec);
    }

    private Path pathFor(Long customerId, String meetingId, int seq, String codec) {
        safe(meetingId);
        Path p = root.resolve(keyFor(customerId, meetingId, seq, codec)).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("invalid meeting path");
        }
        return p;
    }

    private Path directoryFor(Long customerId, String meetingId) {
        safe(meetingId);
        Path dir = root.resolve(String.valueOf(customerId)).resolve(meetingId).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("invalid meeting path");
        }
        return dir;
    }

    private static void safe(String meetingId) {
        if (meetingId == null || !meetingId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid meeting id");
        }
    }

    private static String extension(String codec) {
        return switch (codec == null ? "" : codec.toLowerCase(Locale.ROOT)) {
            case "opus" -> "opus";
            case "adpcm" -> "adpcm";
            default -> "pcm";
        };
    }

    /** 16-bit mono PCM → WAV（44 字节头）。 */
    static byte[] withWavHeader(byte[] pcm, int sampleRate) {
        int channels = 1;
        int bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        int blockAlign = channels * bits / 8;
        int dataLen = pcm.length;
        int riffLen = 36 + dataLen;
        byte[] out = new byte[44 + dataLen];
        writeAscii(out, 0, "RIFF");
        writeIntLe(out, 4, riffLen);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeIntLe(out, 16, 16);
        writeShortLe(out, 20, 1);
        writeShortLe(out, 22, channels);
        writeIntLe(out, 24, sampleRate);
        writeIntLe(out, 28, byteRate);
        writeShortLe(out, 32, blockAlign);
        writeShortLe(out, 34, bits);
        writeAscii(out, 36, "data");
        writeIntLe(out, 40, dataLen);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void writeIntLe(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static void writeShortLe(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }
}
