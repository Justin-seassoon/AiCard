package com.aicard.translation.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BeepAudioTest {

    @Test
    void beepBeepHasExpectedLength() {
        // 100ms beep + 100ms gap + 100ms beep = 300ms = 9600 字节（16kHz × 2 字节）
        byte[] beep = BeepAudio.beepBeep();
        assertThat(beep).hasSize(9600);
    }

    @Test
    void beepIsNonSilent() {
        byte[] beep = BeepAudio.beepBeep();
        boolean hasSound = false;
        for (int i = 0; i < beep.length; i += 2) {
            if (beep[i] != 0 || beep[i + 1] != 0) {
                hasSound = true;
                break;
            }
        }
        assertThat(hasSound).isTrue();
    }
}
