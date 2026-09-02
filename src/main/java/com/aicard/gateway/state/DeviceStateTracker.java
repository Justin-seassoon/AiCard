package com.aicard.gateway.state;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class DeviceStateTracker {

    private final ConcurrentHashMap<String, String> states = new ConcurrentHashMap<>();

    public void markOnline(String deviceId) { states.put(deviceId, "online"); }
    public void markSleep(String deviceId) { states.put(deviceId, "sleep"); }
    public String state(String deviceId) { return states.getOrDefault(deviceId, "offline"); }
}
