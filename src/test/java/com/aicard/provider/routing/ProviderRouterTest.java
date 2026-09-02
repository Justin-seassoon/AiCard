package com.aicard.provider.routing;

import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.metrics.ProviderMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProviderRouterTest {

    private final SpeechProvider primary = mock(SpeechProvider.class);
    private final SpeechProvider fallback = mock(SpeechProvider.class);

    private ProviderRouter router() {
        return new ProviderRouter(primary, fallback, new ProviderMetrics());
    }

    @Test
    void usesPrimaryWhenHealthy() {
        when(primary.name()).thenReturn("azure");
        when(primary.translateToSpeech(any(), any(), any(), any()))
                .thenReturn(new SpeechTranslationResult("hi", "こんにちは"));

        SpeechTranslationResult r = router().translateToSpeech(new byte[]{1}, "en", "ja", b -> {});

        assertThat(r.translatedText()).isEqualTo("こんにちは");
        verify(primary).translateToSpeech(any(), any(), any(), any());
        verifyNoInteractions(fallback);
    }

    @Test
    void fallsBackWhenPrimaryFails() {
        when(primary.name()).thenReturn("azure");
        when(fallback.name()).thenReturn("google");
        when(primary.translateToSpeech(any(), any(), any(), any())).thenThrow(new ProviderException("azure down"));
        when(fallback.translateToSpeech(any(), any(), any(), any()))
                .thenReturn(new SpeechTranslationResult("hi", "fallback-ok"));

        SpeechTranslationResult r = router().translateToSpeech(new byte[]{1}, "en", "ja", b -> {});

        assertThat(r.translatedText()).isEqualTo("fallback-ok");
    }

    @Test
    void propagatesWhenBothFail() {
        when(primary.name()).thenReturn("azure");
        when(fallback.name()).thenReturn("google");
        when(primary.translateToSpeech(any(), any(), any(), any())).thenThrow(new ProviderException("azure down"));
        when(fallback.translateToSpeech(any(), any(), any(), any())).thenThrow(new ProviderException("google down"));

        assertThatThrownBy(() -> router().translateToSpeech(new byte[]{1}, "en", "ja", b -> {}))
                .isInstanceOf(ProviderException.class);
    }
}
