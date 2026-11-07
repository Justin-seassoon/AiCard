package com.aicard.provider.routing;

import com.aicard.provider.api.ProviderException;
import com.aicard.provider.api.SpeechProvider;
import com.aicard.provider.api.SpeechTranslationResult;
import com.aicard.provider.metrics.ProviderMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProviderRouterTest {

    private final SpeechProvider primary = mock(SpeechProvider.class);
    private final SpeechProvider fallback = mock(SpeechProvider.class);

    @Test
    void streamsPrimaryTtsWhenHealthy() {
        when(primary.name()).thenReturn("azure");
        doAnswer(inv -> {
            inv.<Consumer<byte[]>>getArgument(2).accept(new byte[]{1});
            return null;
        }).when(primary).synthesizeStream(anyString(), anyString(), any());

        List<byte[]> chunks = new ArrayList<>();
        router().synthesizeStream("こんにちは", "ja", chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).containsExactly(1);
        verifyNoInteractions(fallback);
    }

    @Test
    void fallsBackOnStreamingTtsWhenNoAudioEmittedYet() {
        when(primary.name()).thenReturn("azure");
        when(fallback.name()).thenReturn("google");
        doThrow(new ProviderException("azure down"))
                .when(primary).synthesizeStream(anyString(), anyString(), any());
        doAnswer(inv -> {
            inv.<Consumer<byte[]>>getArgument(2).accept(new byte[]{2});
            return null;
        }).when(fallback).synthesizeStream(anyString(), anyString(), any());

        List<byte[]> chunks = new ArrayList<>();
        router().synthesizeStream("こんにちは", "ja", chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).containsExactly(2);
    }

    @Test
    void doesNotFallBackWhenAudioAlreadyEmitted() {
        when(primary.name()).thenReturn("azure");
        doAnswer(inv -> {
            inv.<Consumer<byte[]>>getArgument(2).accept(new byte[]{1});
            throw new ProviderException("azure down mid-stream");
        }).when(primary).synthesizeStream(anyString(), anyString(), any());

        assertThatThrownBy(() -> router().synthesizeStream("こんにちは", "ja", b -> {}))
                .isInstanceOf(ProviderException.class);
        verify(fallback, never()).synthesizeStream(anyString(), anyString(), any());
    }

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
