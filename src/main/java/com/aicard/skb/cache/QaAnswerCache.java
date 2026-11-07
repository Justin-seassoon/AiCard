package com.aicard.skb.cache;

import com.aicard.skb.service.SkbService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 高频问答答案缓存：缓存「已通过质量校验」的完整问答结果（含 no_match 兜底结论），
 * 命中时跳过 embedding + 检索 + LLM + 校验，直接回文本并进入 TTS。
 * 键 = 租户 + 域 + 员工语言 + 规范化问题；知识库写入时整体失效，另设 TTL 兜底。
 */
public class QaAnswerCache {

    public record Key(Long customerId, Long storeId, String domain, String targetLang,
                      double retrievalThreshold, String question) {}

    private record Entry(SkbService.LocalizedAnswer value, long expiresAt) {}

    private final boolean enabled;
    private final long ttlMs;
    private final int maxEntries;
    private final Map<Key, Entry> entries;
    private long hits;
    private long misses;

    public QaAnswerCache(boolean enabled, long ttlSeconds, int maxEntries) {
        this.enabled = enabled;
        this.ttlMs = Math.max(1, ttlSeconds) * 1000L;
        this.maxEntries = Math.max(1, maxEntries);
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                return size() > QaAnswerCache.this.maxEntries;
            }
        };
    }

    public static Key key(Long customerId, Long storeId, String domain, String targetLang,
                          double retrievalThreshold, String question) {
        return new Key(customerId, storeId, domain, normalize(targetLang), retrievalThreshold,
                normalize(question));
    }

    public synchronized Optional<SkbService.LocalizedAnswer> get(Key key) {
        if (!enabled) {
            return Optional.empty();
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            misses++;
            return Optional.empty();
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            entries.remove(key);
            misses++;
            return Optional.empty();
        }
        hits++;
        return Optional.of(entry.value());
    }

    public synchronized void put(Key key, SkbService.LocalizedAnswer value) {
        if (!enabled || value == null) {
            return;
        }
        entries.put(key, new Entry(value, System.currentTimeMillis() + ttlMs));
    }

    /** 知识库写入（导入 / 发布 / 审核通过）后调用，避免播报过期答案。 */
    public synchronized void invalidateAll() {
        entries.clear();
    }

    public synchronized Stats stats() {
        return new Stats(hits, misses, entries.size());
    }

    public record Stats(long hits, long misses, int size) {}

    private static String normalize(String s) {
        return s == null ? null : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
