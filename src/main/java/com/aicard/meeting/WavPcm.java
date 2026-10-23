package com.aicard.meeting;

/**
 * 极简 WAV 解析：仅支持 16-bit PCM（单/多声道），统一转为 16kHz 单声道 PCM16LE。
 * <p>用于「音频导入」：无麦克风 / HTTP 环境 / 会议系统录音文件均可走同一套转写链路。</p>
 * <p>文件按不可信数据处理：只读 fmt/data 块，限制总时长，不做任意解码。</p>
 */
public final class WavPcm {

    private static final int TARGET_RATE = 16000;
    private static final int MAX_SECONDS = 2 * 3600;

    private WavPcm() {}

    public record Audio(byte[] pcm16, int seconds) {}

    public static Audio parse(byte[] wav) {
        if (wav == null || wav.length < 44
                || !"RIFF".equals(ascii(wav, 0, 4)) || !"WAVE".equals(ascii(wav, 8, 4))) {
            throw new IllegalArgumentException("WAV ファイルではありません");
        }
        int fmtOffset = -1;
        int channels = 1;
        int sampleRate = TARGET_RATE;
        int bits = 16;
        int dataOffset = -1;
        int dataLen = 0;

        int off = 12;
        while (off + 8 <= wav.length) {
            String id = ascii(wav, off, 4);
            int size = intLe(wav, off + 4);
            int body = off + 8;
            if (size < 0 || body > wav.length) {
                break;
            }
            if ("fmt ".equals(id) && body + 16 <= wav.length) {
                fmtOffset = body;
                channels = shortLe(wav, body + 2);
                sampleRate = intLe(wav, body + 4);
                bits = shortLe(wav, body + 14);
            } else if ("data".equals(id)) {
                dataOffset = body;
                dataLen = Math.min(size, wav.length - body);
                break;
            }
            off = body + size + (size & 1); // chunk 按 word 对齐
        }

        if (fmtOffset < 0 || dataOffset < 0 || dataLen <= 0) {
            throw new IllegalArgumentException("WAV の fmt/data チャンクが見つかりません");
        }
        int format = shortLe(wav, fmtOffset);
        if (format != 1 || bits != 16) {
            throw new IllegalArgumentException("PCM 16-bit の WAV のみ対応しています（format=" + format + ", bits=" + bits + "）");
        }
        if (channels < 1 || channels > 8 || sampleRate < 8000 || sampleRate > 96000) {
            throw new IllegalArgumentException("未対応のチャンネル数/サンプリングレートです");
        }

        int frames = dataLen / (2 * channels);
        int maxFrames = MAX_SECONDS * sampleRate;
        if (frames > maxFrames) {
            throw new IllegalArgumentException("音声が長すぎます（最大 2 時間）");
        }
        int outFrames = (int) Math.round((double) frames * TARGET_RATE / sampleRate);
        byte[] out = new byte[outFrames * 2];
        for (int i = 0; i < outFrames; i++) {
            double srcPos = (double) i * sampleRate / TARGET_RATE;
            int idx = (int) Math.floor(srcPos);
            double frac = srcPos - idx;
            int s0 = frameSample(wav, dataOffset, dataLen, idx, channels);
            int s1 = frameSample(wav, dataOffset, dataLen, Math.min(idx + 1, frames - 1), channels);
            int mixed = (int) Math.round(s0 + (s1 - s0) * frac);
            out[i * 2] = (byte) (mixed & 0xff);
            out[i * 2 + 1] = (byte) ((mixed >> 8) & 0xff);
        }
        return new Audio(out, outFrames / TARGET_RATE);
    }

    /** 多声道取平均（下混），越界返回 0。 */
    private static int frameSample(byte[] wav, int dataOffset, int dataLen, int frame, int channels) {
        int base = dataOffset + frame * channels * 2;
        if (base < dataOffset || base + channels * 2 > dataOffset + dataLen) {
            return 0;
        }
        int sum = 0;
        for (int c = 0; c < channels; c++) {
            sum += shortLe(wav, base + c * 2);
        }
        return sum / channels;
    }

    private static String ascii(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len && off + i < b.length; i++) {
            sb.append((char) (b[off + i] & 0xff));
        }
        return sb.toString();
    }

    private static int intLe(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int shortLe(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }
}
