package com.aicard.provider.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 供应商成本/成败计量：按 (供应商, 能力) 维度累计成功/失败次数。
 */
public class ProviderMetrics {

    public static final class Key {
        public final String provider;
        public final String capability;
        public Key(String provider, String capability) { this.provider = provider; this.capability = capability; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return k.provider.equals(provider) && k.capability.equals(capability);
        }
        @Override public int hashCode() { return 31 * provider.hashCode() + capability.hashCode(); }
    }

    private final Map<Key, AtomicLong> success = new ConcurrentHashMap<>();
    private final Map<Key, AtomicLong> failure = new ConcurrentHashMap<>();

    public void recordSuccess(String provider, String capability) {
        success.computeIfAbsent(new Key(provider, capability), k -> new AtomicLong()).incrementAndGet();
    }

    public void recordFailure(String provider, String capability) {
        failure.computeIfAbsent(new Key(provider, capability), k -> new AtomicLong()).incrementAndGet();
    }

    public long successCount(String provider, String capability) {
        var c = success.get(new Key(provider, capability));
        return c == null ? 0 : c.get();
    }

    public long failureCount(String provider, String capability) {
        var c = failure.get(new Key(provider, capability));
        return c == null ? 0 : c.get();
    }
}
