package com.aicard.skb.seed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordExtractorTest {

    private final KeywordExtractor extractor = new KeywordExtractor();

    @Test
    void extractsCoreNouns() {
        assertThat(extractor.extract("朝食は何時からですか")).contains("朝食");
        assertThat(extractor.extract("免税の対象になるのは誰ですか")).contains("免税");
    }

    @Test
    void dropsParticlesAndQuestionWords() {
        assertThat(extractor.extract("朝食は何時からですか"))
                .doesNotContain("は", "か", "です", "何時", "から");
    }

    @Test
    void returnsEmptyForBlankOrPureStopWords() {
        assertThat(extractor.extract("")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("ですか")).isEmpty();
    }
}
