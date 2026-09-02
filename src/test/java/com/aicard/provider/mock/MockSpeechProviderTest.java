package com.aicard.provider.mock;

import com.aicard.provider.api.LidResult;
import com.aicard.provider.api.ProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockSpeechProviderTest {

    @Test
    void returnsConfiguredResults() {
        MockSpeechProvider mock = new MockSpeechProvider();
        mock.setLidResult(new LidResult("en-US", 0.95));
        mock.setFinalText("hello");
        mock.setTranslatedText("こんにちは");
        mock.setTtsAudio(new byte[]{1, 2, 3});

        assertThat(mock.name()).isEqualTo("mock");
        assertThat(mock.detectLanguage(new byte[]{9}, List.of("ja-JP", "en-US")))
                .isEqualTo(new LidResult("en-US", 0.95));
        byte[][] out = new byte[1][];
        assertThat(mock.translateToSpeech(new byte[]{9}, "en-US", "ja-JP", audio -> out[0] = audio).translatedText())
                .isEqualTo("こんにちは");
        assertThat(out[0]).containsExactly(1, 2, 3);
    }

    @Test
    void failAllThrows() {
        MockSpeechProvider mock = new MockSpeechProvider();
        mock.setFailAll(true);

        assertThatThrownBy(() -> mock.detectLanguage(new byte[]{9}, List.of("ja-JP")))
                .isInstanceOf(ProviderException.class);
    }
}
