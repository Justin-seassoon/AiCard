package com.aicard.skb.curation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GapCaptureServiceTest {

    private static GapCaptureService.Row row(String q, long epochSec) {
        return new GapCaptureService.Row(q, Instant.ofEpochSecond(epochSec));
    }

    @Test
    void mergesSameQuestionAfterNormalization() {
        var aggs = GapCaptureService.aggregate(List.of(
                row("朝食は何時からですか？", 100),
                row("朝食は何時からですか", 200),
                row(" 朝食は何時からですか。 ", 300)));

        assertThat(aggs).hasSize(1);
        assertThat(aggs.get(0).occurrences()).isEqualTo(3);
        // 代表问法取「最早见到」那一条
        assertThat(aggs.get(0).question()).isEqualTo("朝食は何時からですか？");
        assertThat(aggs.get(0).firstSeen()).isEqualTo(Instant.ofEpochSecond(100));
    }

    @Test
    void keepsDifferentQuestionsSeparate() {
        var aggs = GapCaptureService.aggregate(List.of(
                row("朝食は何時ですか", 100),
                row("チェックアウトは何時ですか", 100)));
        assertThat(aggs).hasSize(2);
    }

    @Test
    void sortsByOccurrencesDescending() {
        var aggs = GapCaptureService.aggregate(List.of(
                row("Aは？", 1), row("Bは？", 1), row("Bは？", 2)));
        assertThat(aggs.get(0).question()).isEqualTo("Bは？");
        assertThat(aggs.get(0).occurrences()).isEqualTo(2);
    }

    @Test
    void ignoresBlankQuestions() {
        var aggs = GapCaptureService.aggregate(List.of(row("   ", 1), row("です", 1)));
        assertThat(aggs).hasSize(1);
        assertThat(aggs.get(0).question()).isEqualTo("です");
    }

    @Test
    void normalizeStripsWhitespacePunctuationAndCase() {
        assertThat(GapCaptureService.normalize(" ABC、です。 ")).isEqualTo("abcです");
        assertThat(GapCaptureService.normalize(null)).isEmpty();
    }

    @Test
    void keyIsStableHex64AndInsensitiveToPunctuation() {
        String k1 = GapCaptureService.key(GapCaptureService.normalize("朝食は何時からですか？"));
        String k2 = GapCaptureService.key(GapCaptureService.normalize("朝食は何時からですか。"));
        assertThat(k1).isEqualTo(k2).hasSize(64);
    }
}
