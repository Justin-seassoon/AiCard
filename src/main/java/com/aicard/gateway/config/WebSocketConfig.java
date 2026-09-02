package com.aicard.gateway.config;

import com.aicard.gateway.auth.DeviceAuthenticator;
import com.aicard.gateway.auth.GatewayHandshakeInterceptor;
import com.aicard.gateway.handler.GatewayTransportHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WSS 接入配置：原始 WebSocket（非 STOMP），端点 /ws，握手鉴权在 interceptor 完成。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GatewayTransportHandler transportHandler;
    private final DeviceAuthenticator authenticator;

    public WebSocketConfig(GatewayTransportHandler transportHandler, DeviceAuthenticator authenticator) {
        this.transportHandler = transportHandler;
        this.authenticator = authenticator;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(transportHandler, "/ws")
                .addInterceptors(new GatewayHandshakeInterceptor(authenticator))
                .setAllowedOriginPatterns("*");
    }
}
