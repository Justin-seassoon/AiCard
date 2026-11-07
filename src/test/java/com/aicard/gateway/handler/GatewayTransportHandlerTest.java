package com.aicard.gateway.handler;

import com.aicard.broadcast.service.BroadcastService;
import com.aicard.common.domain.Device;
import com.aicard.gateway.auth.GatewayHandshakeInterceptor;
import com.aicard.gateway.session.SessionContext;
import com.aicard.gateway.session.SessionManager;
import com.aicard.gateway.state.DeviceConnectionRegistry;
import com.aicard.gateway.state.DeviceStateTracker;
import com.aicard.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayTransportHandlerTest {

    private static final String ATTR_SESSION = "sessionContext";

    // 与运行时一致：全局 snake_case（application.yml spring.jackson.property-naming-strategy）
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final SessionManager sessions = mock(SessionManager.class);
    private final GatewayWebSocketHandler router = mock(GatewayWebSocketHandler.class);
    private final DeviceStateTracker states = mock(DeviceStateTracker.class);
    private final DeviceConnectionRegistry connections = mock(DeviceConnectionRegistry.class);
    private final BroadcastService broadcasts = mock(BroadcastService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private final GatewayTransportHandler handler = new GatewayTransportHandler(
            mapper, sessions, router, states, connections, broadcasts, notifications);

    private static Device device() {
        return Device.builder().deviceId("dev-1").customerId(1L).storeId(2L).token("t").build();
    }

    private static WebSocketSession sessionWithAttrs(Map<String, Object> attrs) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }

    @Test
    void afterConnectionEstablishedMarksOnlineAndRegisters() {
        WebSocketSession session = sessionWithAttrs(attrs(GatewayHandshakeInterceptor.ATTR_DEVICE, device()));

        handler.afterConnectionEstablished(session);

        verify(states).markOnline("dev-1");
        verify(connections).register("dev-1", session);
    }

    @Test
    void afterConnectionEstablishedDoesNothingWithoutDevice() {
        WebSocketSession session = sessionWithAttrs(new HashMap<>());

        handler.afterConnectionEstablished(session);

        verify(states, never()).markOnline(any());
        verify(connections, never()).register(any(), any());
    }

    @Test
    void sessionInitCreatesSessionAndDeliversPending() {
        WebSocketSession session = sessionWithAttrs(attrs(GatewayHandshakeInterceptor.ATTR_DEVICE, device()));
        SessionContext ctx = mock(SessionContext.class);
        when(sessions.create(eq("s1"), eq("dev-1"), eq(1L), eq(2L),
                anyString(), anyString(), anyString(), any(), any())).thenReturn(ctx);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"session_init\",\"session_id\":\"s1\"}"));

        verify(sessions).create(eq("s1"), eq("dev-1"), eq(1L), eq(2L),
                anyString(), anyString(), anyString(), any(), any());
        verify(states).markOnline("dev-1");
        verify(broadcasts).deliverPending("dev-1");
        verify(notifications).deliverPending("dev-1");
    }

    @Test
    void sleepNoticeMarksSleep() {
        SessionContext ctx = mock(SessionContext.class);
        when(ctx.deviceId()).thenReturn("dev-1");
        WebSocketSession session = sessionWithAttrs(attrs(ATTR_SESSION, ctx));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"sleep_notice\",\"session_id\":\"s1\"}"));

        verify(states).markSleep("dev-1");
    }

    @Test
    void afterConnectionClosedCleansUpSessionAndState() {
        SessionContext ctx = mock(SessionContext.class);
        when(ctx.sessionId()).thenReturn("s1");
        when(ctx.deviceId()).thenReturn("dev-1");
        WebSocketSession session = sessionWithAttrs(attrs(ATTR_SESSION, ctx));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(sessions).remove("s1");
        verify(states).markSleep("dev-1");
        verify(connections).unregister("dev-1");
    }

    @Test
    void afterConnectionClosedDoesNothingWithoutSession() {
        WebSocketSession session = sessionWithAttrs(new HashMap<>());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(sessions, never()).remove(anyString());
        verify(connections, never()).unregister(any());
    }

    private static Map<String, Object> attrs(String key, Object value) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(key, value);
        return attrs;
    }
}
