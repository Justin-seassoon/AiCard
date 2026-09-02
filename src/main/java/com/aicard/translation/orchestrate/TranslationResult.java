package com.aicard.translation.orchestrate;

public record TranslationResult(
        String action,           // translate | repeat | conflict | no_translate
        String errorCode,
        String newVisitorLanguage
) {}
