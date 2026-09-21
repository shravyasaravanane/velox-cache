package com.velox.core;

import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.FakeTicker;
import com.velox.core.expiry.HeapExpiryEngine;
import com.velox.core.policy.LruPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code get(key, loader)}: the cache-aside call, backed by single-flight.
 *
 * <p>Single-threaded on purpose. The concurrent behaviour is proven in
 * {@code SingleFlightTest} and {@code StampedeTest}; this checks that the cache
 * uses it correctly.
 */
class VeloxCacheLoaderTest {

    private static VeloxCache<String, Integer> cache(int capacity) {
        return new VeloxCache<>(capacity, new LruPolicy<>());
    }

    @Test
    @DisplayName("a failing loader propagates, caches nothing, and the next call retries")
    void failedLoadIsNotCachedAndIsRetried() {
        var cache = cache(3);

        var thrown = assertThrows(IllegalStateException.class,
                () -> cache.get("A", key -> {
                    throw new IllegalStateException("database is down");
                }));

        assertEquals("database is down", thrown.getMessage());
        assertEquals(0, cache.size(), "a failure must not leave anything cached");
        assertEquals(1, cache.stats().loadCount());
        assertEquals(1, cache.stats().loadFailureCount());

        assertEquals(5, cache.get("A", key -> 5), "the next call starts a fresh attempt");
        assertEquals(2, cache.stats().loadCount());
        assertEquals(1, cache.stats().loadFailureCount(), "only the first attempt failed");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a hit does not run the loader at all")
    void hitSkipsTheLoader() {
        var cache = cache(3);
        cache.put("A", 1);

        int value = cache.get("A", key -> {
            throw new AssertionError("the loader must not run on a hit");
        });

        assertEquals(1, value);
        assertEquals(0, cache.stats().loadCount());
    }

    @Test
    @DisplayName("a null result is returned but never cached")
    void nullResultIsNotCached() {
        var cache = cache(3);

        assertNull(cache.get("A", key -> null));

        assertEquals(0, cache.size());
        assertEquals(1, cache.stats().loadCount(), "the loader did run, and that is counted");
    }

    @Test
    @DisplayName("a loader may load other keys through the same cache")
    void nestedLoadsOfOtherKeys() {
        var cache = cache(5);

        int a = cache.get("A", key -> 1 + cache.get("B", inner -> 10));

        assertEquals(11, a);
        assertEquals(10, cache.getIfPresent("B"));
        assertEquals(11, cache.getIfPresent("A"));
        assertEquals(2, cache.stats().loadCount());
        cache.assertInvariants();
    }

    @Test
    // If recursion detection ever breaks, this test DEADLOCKS (the thread waits on itself).
    // Without a timeout that would hang the whole build instead of failing it.
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("a loader requesting its own key fails loudly, and the cache stays usable")
    void recursiveLoadIsRejected() {
        var cache = cache(3);

        var thrown = assertThrows(IllegalStateException.class,
                () -> cache.get("A", key -> cache.get("A", again -> 1)));

        assertTrue(thrown.getMessage().contains("recursive"), thrown.getMessage());
        assertEquals(7, cache.get("Z", key -> 7), "the cache must not be left wedged by the failed attempt");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("an expired entry is reloaded on the next get")
    void expiredEntryIsReloaded() {
        var clock = new FakeTicker();
        var cache = new VeloxCache<String, Integer>(
                10, new LruPolicy<>(),
                new ExpiryConfig(Duration.ofSeconds(10).toNanos(), -1, 0),
                clock, new HeapExpiryEngine<>());
        List<String> loads = new ArrayList<>();

        cache.get("A", key -> {
            loads.add("first");
            return 1;
        });
        cache.get("A", key -> {
            loads.add("unexpected");
            return 99;
        });
        clock.advance(Duration.ofSeconds(11));
        int reloaded = cache.get("A", key -> {
            loads.add("second");
            return 2;
        });

        assertEquals(List.of("first", "second"), loads, "one load, a hit, then a reload after expiry");
        assertEquals(2, reloaded);
        assertEquals(2, cache.stats().loadCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("single-threaded use never coalesces anything")
    void noCoalescingWithoutConcurrency() {
        var cache = cache(3);

        cache.get("A", key -> 1);
        cache.get("B", key -> 2);

        assertEquals(0, cache.stats().coalescedCount());
    }
}
