package com.aicard.gateway.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceStateTrackerTest {

    @Test
    void tracksOnlineAndSleep() {
        DeviceStateTracker t = new DeviceStateTracker();
        t.markOnline("dev-1");
        assertThat(t.state("dev-1")).isEqualTo("online");
        t.markSleep("dev-1");
        assertThat(t.state("dev-1")).isEqualTo("sleep");
    }

    @Test
    void unknownDeviceIsOffline() {
        assertThat(new DeviceStateTracker().state("nope")).isEqualTo("offline");
    }
}
