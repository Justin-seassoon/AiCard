package com.aicard.gateway.auth;

import com.aicard.common.domain.Device;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayHandshakeInterceptorTest {

    private final DeviceAuthenticator authenticator = mock(DeviceAuthenticator.class);
    private final GatewayHandshakeInterceptor interceptor = new GatewayHandshakeInterceptor(authenticator);

    @Test
    void rejectsNonServletRequest() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);

        assertThat(interceptor.beforeHandshake(request, null, null, new HashMap<>())).isFalse();
    }

    @Test
    void rejectsMissingDeviceId() {
        ServletServerHttpRequest request = servletRequest(null, "t");

        assertThat(interceptor.beforeHandshake(request, null, null, new HashMap<>())).isFalse();
    }

    @Test
    void rejectsMissingToken() {
        ServletServerHttpRequest request = servletRequest("dev-1", null);

        assertThat(interceptor.beforeHandshake(request, null, null, new HashMap<>())).isFalse();
    }

    @Test
    void authenticatesAndStoresDevice() {
        ServletServerHttpRequest request = servletRequest("dev-1", "t");
        Device device = Device.builder().deviceId("dev-1").build();
        when(authenticator.authenticate("dev-1", "t")).thenReturn(device);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request, null, mock(WebSocketHandler.class), attrs);

        assertThat(accepted).isTrue();
        assertThat(attrs.get(GatewayHandshakeInterceptor.ATTR_DEVICE)).isEqualTo(device);
    }

    @Test
    void rejectsOnAuthenticationFailure() {
        ServletServerHttpRequest request = servletRequest("dev-1", "bad");
        when(authenticator.authenticate("dev-1", "bad"))
                .thenThrow(new AuthenticationException("invalid token"));

        assertThat(interceptor.beforeHandshake(request, null, null, new HashMap<>())).isFalse();
    }

    private static ServletServerHttpRequest servletRequest(String deviceId, String token) {
        ServletServerHttpRequest request = mock(ServletServerHttpRequest.class);
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(request.getServletRequest()).thenReturn(http);
        when(http.getParameter("device_id")).thenReturn(deviceId);
        when(http.getParameter("token")).thenReturn(token);
        return request;
    }
}
