package com.aicard.gateway.push;

import com.aicard.gateway.protocol.OutboundMessage;
import com.aicard.gateway.state.DeviceConnectionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevicePusherTest {

    private final DeviceConnectionRegistry connections = mock(DeviceConnectionRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DevicePusher pusher = new DevicePusher(connections, mapper);

    @Test
    void sendsTextThenChunkedTtsThenEnd() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        OutboundMessage msg = OutboundMessage.builder().type("broadcast").broadcastId(9L)
                .text("你好").targetLanguage("ja-JP").build();
        byte[] tts = new byte[9000]; // >8KB，分 2 片

        boolean ok = pusher.send(session, msg, tts, "bc9");

        assertThat(ok).isTrue();
        // 1 文本 + 2 二进制分片 + 1 tts_end 文本 = 4 次
        verify(session, times(4)).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void sendToDeviceReturnsFalseWhenOffline() {
        when(connections.findOnline("dev-x")).thenReturn(Optional.empty());

        boolean ok = pusher.sendToDevice("dev-x",
                OutboundMessage.builder().type("broadcast").build(), new byte[0], "x");

        assertThat(ok).isFalse();
    }
}
