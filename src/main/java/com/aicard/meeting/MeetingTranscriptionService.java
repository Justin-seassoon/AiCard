package com.aicard.meeting;

import com.aicard.provider.api.SpeechProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会议转写。
 * <p>P0：把分片按 60 秒分组交给现有 {@link SpeechProvider#transcribe}——可跑通、可演示；
 * Mock 供应商返回固定的日语会议转写（演示/测试用）。</p>
 * <p>P1：接入 Azure Batch Transcription（长音频 + 说话人分离），接口保持不变。</p>
 * <p>⚠️ 负责人/期限只从转写中**明确出现**的语句提取，不推测说话人身份。</p>
 */
@Service
public class MeetingTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(MeetingTranscriptionService.class);

    private final SpeechProvider speech;
    private final MeetingConfig config;
    private final MeetingAudioStore audioStore;

    public MeetingTranscriptionService(SpeechProvider speech, MeetingConfig config, MeetingAudioStore audioStore) {
        this.speech = speech;
        this.config = config;
        this.audioStore = audioStore;
    }

    public MeetingTranscript transcribe(Meeting meeting, List<MeetingChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return MeetingTranscript.empty();
        }
        if ("mock".equalsIgnoreCase(speech.name())) {
            return demoTranscript();
        }

        List<MeetingTranscript.Segment> segments = new ArrayList<>();
        int failed = 0;
        int i = 0;
        while (i < chunks.size()) {
            // 单次转写最多 ~50 秒（Azure 短音频 REST 上限 60 秒）
            List<MeetingChunk> group = new ArrayList<>();
            int maxSec = Math.max(5, Math.min(meeting.getChunkSec(), 30));
            while (i < chunks.size() && (group.size() + 1) * maxSec <= 50) {
                group.add(chunks.get(i++));
            }
            int startSec = group.get(0).getSeq() * meeting.getChunkSec();
            int endSec = (group.get(group.size() - 1).getSeq() + 1) * meeting.getChunkSec();
            try {
                byte[] pcm = concat(group);
                String text = speech.transcribe(pcm, config.getLanguage());
                if (text != null && !text.isBlank()) {
                    segments.add(new MeetingTranscript.Segment(startSec, endSec, null, text.trim()));
                }
            } catch (Exception e) {
                failed++;
                log.warn("meeting transcription failed meeting={} seq={}: {}", meeting.getMeetingId(),
                        group.get(0).getSeq(), e.getMessage());
            }
        }
        if (segments.isEmpty() && failed > 0) {
            throw new IllegalStateException("転写に失敗しました（全 " + failed + " 区間）");
        }
        return new MeetingTranscript(toText(segments), segments);
    }

    private byte[] concat(List<MeetingChunk> group) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (MeetingChunk c : group) {
            out.writeBytes(audioStore.read(c.getStorageKey()));
        }
        return out.toByteArray();
    }

    public static String toText(List<MeetingTranscript.Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (MeetingTranscript.Segment s : segments) {
            sb.append('[').append(ref(s.startSec())).append(']');
            if (s.speaker() != null && !s.speaker().isBlank()) {
                sb.append(' ').append(s.speaker()).append(':');
            }
            sb.append(' ').append(s.text()).append('\n');
        }
        return sb.toString().trim();
    }

    public static String ref(int sec) {
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        int s = sec % 60;
        return h > 0
                ? String.format("%02d:%02d:%02d", h, m, s)
                : String.format("%02d:%02d", m, s);
    }

    /** 演示用固定转写（Mock 供应商）。 */
    static MeetingTranscript demoTranscript() {
        List<MeetingTranscript.Segment> segs = List.of(
                new MeetingTranscript.Segment(0, 18, "司会（田中）",
                        "それでは、本日の定例会議を始めます。議題は、経費精算の締め日変更と、来月の棚卸しの進め方です。"),
                new MeetingTranscript.Segment(18, 30, "佐藤",
                        "経費精算について、総務から提案があります。"),
                new MeetingTranscript.Segment(30, 52, "鈴木（総務）",
                        "現在は毎月25日締めですが、交通費の入力遅れが多く、月末締めに変更したいと考えています。"),
                new MeetingTranscript.Segment(52, 68, "田中",
                        "月末締めにすると、経理の処理は翌月3日までかかりますが、問題ありませんか。"),
                new MeetingTranscript.Segment(68, 80, "鈴木（総務）",
                        "問題ありません。翌月3日までに処理できる見込みです。"),
                new MeetingTranscript.Segment(80, 102, "田中",
                        "では、来月から月末締めに変更しましょう。社内通知の作成は鈴木さん、10月30日までにお願いします。"),
                new MeetingTranscript.Segment(102, 120, "佐藤",
                        "棚卸しは11月10日の週に実施したいです。各店舗の担当者は店長に確認します。"),
                new MeetingTranscript.Segment(120, 132, "田中",
                        "実施計画の作成は佐藤さん、11月5日までにお願いします。"),
                new MeetingTranscript.Segment(132, 144, "鈴木（総務）",
                        "備品の在庫確認も同時に行います。担当は私がやります。"),
                new MeetingTranscript.Segment(144, 160, "田中",
                        "本日の決定事項は以上です。次回は11月7日10時から、本会議室で行います。"));
        return new MeetingTranscript(toText(segs), segs);
    }
}
