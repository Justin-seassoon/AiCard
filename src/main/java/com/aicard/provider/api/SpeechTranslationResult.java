package com.aicard.provider.api;

/** 语音翻译结果：源语言识别文本 + 目标语言翻译文本（ASR+MT 一步）。 */
public record SpeechTranslationResult(String finalText, String translatedText) {}
