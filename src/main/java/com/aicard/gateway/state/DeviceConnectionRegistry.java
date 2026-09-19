package com.aicard.gateway.state;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备 → 活跃 WebSocket 连接注册表。广播等「云端主动下行」场景据此找到在线设备。
 * 设备连接即注册、断开即注销（见 GatewayTransportHandler）。
 */
@Component
public class DeviceConnectionRegistry {

    private final ConcurrentHashMap<String, WebSocketSession> connections = new ConcurrentHashMap<>();

    public void register(String deviceId, WebSocketSession session) {
        connections.put(deviceId, session);
    }

    public void unregister(String deviceId) {
        connections.remove(deviceId);
    }

    public Optional<WebSocketSession> findOnline(String deviceId) {
        return Optional.ofNullable(connections.get(deviceId));
    }
}
