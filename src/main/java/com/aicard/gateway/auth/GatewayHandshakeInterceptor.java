package com.aicard.gateway.auth;

import com.aicard.common.domain.Device;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WSS 握手鉴权：从 query 参数取 device_id + token，验通过后把 Device 写入
 * attributes，供 transport 层推导 tenant。鉴权失败拒绝握手。
 */
public class GatewayHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_DEVICE = "device";

    private final DeviceAuthenticator authenticator;

    public GatewayHandshakeInterceptor(DeviceAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        String deviceId = servletRequest.getServletRequest().getParameter("device_id");
        String token = servletRequest.getServletRequest().getParameter("token");
        if (deviceId == null || token == null) {
            return false;
        }
        try {
            Device device = authenticator.authenticate(deviceId, token);
            attributes.put(ATTR_DEVICE, device);
            return true;
        } catch (AuthenticationException e) {
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
