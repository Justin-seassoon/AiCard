package com.aicard.skb.cache;

import com.aicard.skb.model.SkbResult;
import com.aicard.skb.service.SkbService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QaAnswerCacheTest {

    private static final double THETA = 0.55;

    private static SkbService.LocalizedAnswer answer(String text) {
        return new SkbService.LocalizedAnswer(SkbResult.ok(text, null), text);
    }

    @Test
    void returnsCachedValueForSameKey() {
        QaAnswerCache cache = new QaAnswerCache(true, 300, 10);
        QaAnswerCache.Key key = QaAnswerCache.key(1L, 2L, "skb", "zh-CN", THETA, "  早餐几点开始？ ");

        cache.put(key, answer("早餐7点开始。"));

        assertThat(cache.get(QaAnswerCache.key(1L, 2L, "skb", "zh-CN", THETA, "早餐几点开始？")))
                .isPresent()
                .get()
                .extracting(SkbService.LocalizedAnswer::localizedAnswer)
                .isEqualTo("早餐7点开始。");
    }

    @Test
    void separatesKeysByTenantLanguageAndThreshold() {
        QaAnswerCache cache = new QaAnswerCache(true, 300, 10);
        cache.put(QaAnswerCache.key(1L, 2L, "skb", "zh-CN", THETA, "q"), answer("中文"));

        assertThat(cache.get(QaAnswerCache.key(1L, 2L, "skb", "en-US", THETA, "q"))).isEmpty();
        assertThat(cache.get(QaAnswerCache.key(9L, 2L, "skb", "zh-CN", THETA, "q"))).isEmpty();
        assertThat(cache.get(QaAnswerCache.key(1L, 2L, "skb", "zh-CN", 0.45, "q"))).isEmpty();
        assertThat(cache.get(QaAnswerCache.key(1L, 2L, "skb", "zh-CN", THETA, "q"))).isPresent();
    }

    @Test
    void invalidateAllDropsEntries() {
        QaAnswerCache cache = new QaAnswerCache(true, 300, 10);
        QaAnswerCache.Key key = QaAnswerCache.key(1L, 2L, "skb", null, THETA, "q");
        cache.put(key, answer("a"));

        cache.invalidateAll();

        assertThat(cache.get(key)).isEmpty();
        assertThat(cache.stats().size()).isZero();
    }

    @Test
    void evictsBeyondMaxEntries() {
        QaAnswerCache cache = new QaAnswerCache(true, 300, 2);
        cache.put(QaAnswerCache.key(1L, 1L, "skb", null, THETA, "q1"), answer("a1"));
        cache.put(QaAnswerCache.key(1L, 1L, "skb", null, THETA, "q2"), answer("a2"));
        cache.put(QaAnswerCache.key(1L, 1L, "skb", null, THETA, "q3"), answer("a3"));

        assertThat(cache.stats().size()).isEqualTo(2);
    }

    @Test
    void disabledCacheNeverHits() {
        QaAnswerCache cache = new QaAnswerCache(false, 300, 10);
        QaAnswerCache.Key key = QaAnswerCache.key(1L, 2L, "skb", null, THETA, "q");
        cache.put(key, answer("a"));

        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    void expiresAfterTtl() throws Exception {
        QaAnswerCache cache = new QaAnswerCache(true, 1, 10); // ttl 1 秒
        QaAnswerCache.Key key = QaAnswerCache.key(1L, 2L, "skb", "zh-CN", THETA, "q");
        cache.put(key, answer("a"));

        assertThat(cache.get(key)).isPresent();

        Thread.sleep(1100);
        assertThat(cache.get(key)).isEmpty();
        assertThat(cache.stats().size()).isZero();
    }

    @Test
    void putNullIsNoop() {
        QaAnswerCache cache = new QaAnswerCache(true, 300, 10);
        QaAnswerCache.Key key = QaAnswerCache.key(1L, 2L, "skb", null, THETA, "q");

        cache.put(key, null);

        assertThat(cache.get(key)).isEmpty();
        assertThat(cache.stats().size()).isZero();
    }

    @Test
    void normalizesQuestionCaseAndInnerWhitespace() {
        QaAnswerCache cache = new QaAnswerCache(true, 300, 10);
        cache.put(QaAnswerCache.key(1L, 2L, "skb", "zh-CN", THETA, "Breakfast   Time"), answer("a"));

        assertThat(cache.get(QaAnswerCache.key(1L, 2L, "skb", "zh-CN", THETA, "breakfast time")))
                .isPresent();
    }
}
