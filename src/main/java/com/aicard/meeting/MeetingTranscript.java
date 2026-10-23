package com.aicard.meeting;

import java.util.List;

/** 转写结果：全文 + 带时间戳的分段（纪要中的根拠引用）。 */
public record MeetingTranscript(String text, List<Segment> segments) {

    public record Segment(int startSec, int endSec, String speaker, String text) {}

    public static MeetingTranscript empty() {
        return new MeetingTranscript("", List.of());
    }
}
