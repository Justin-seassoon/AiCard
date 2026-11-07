package com.aicard.gateway.state;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeviceConnectionRegistryTest {

    private final DeviceConnectionRegistry registry = new DeviceConnectionRegistry();

    @Test
    void registerThenFindOnlineReturnsSession() {
        WebSocketSession session = mock(WebSocketSession.class);

        registry.register("dev-1", session);

        assertThat(registry.findOnline("dev-1")).hasValue(session);
        assertThat(registry.findOnline("dev-2")).isEmpty();
    }

    @Test
    void unregisterRemovesConnection() {
        registry.register("dev-1", mock(WebSocketSession.class));

        registry.unregister("dev-1");

        assertThat(registry.findOnline("dev-1")).isEmpty();
    }

    @Test
    void reRegisterReplacesSession() {
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);

        registry.register("dev-1", first);
        registry.register("dev-1", second);

        assertThat(registry.findOnline("dev-1")).hasValue(second);
    }
}
