package com.aicard.translation.state;

public record PendingSwitch(String lang, int count) {
    public PendingSwitch bump() { return new PendingSwitch(lang, count + 1); }
}
