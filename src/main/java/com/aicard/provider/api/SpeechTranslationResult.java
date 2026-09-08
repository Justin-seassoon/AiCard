package com.aicard.provider.api;

/**
 * 语音翻译结果：源语言识别文本 + 目标语言翻译文本（ASR+MT 一步）。
 * auto 模式（一步 auto-detect）时，detectedLanguage 为检测到的源语言，否则为 null。
 */
public record SpeechTranslationResult(String finalText, String translatedText, String detectedLanguage) {
    public SpeechTranslationResult(String finalText, String translatedText) {
        this(finalText, translatedText, null);
    }
}
