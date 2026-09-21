package com.velox.core;

import com.velox.core.expiry.AtomicTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The background cleanup thread: expired entries leave a cache that nobody is touching.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ShardedCacheSweeperTest {

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for: " + description);
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    @Test
    @DisplayName("expired entries are removed although nothing is calling the cache")
    void sweeperEmptiesAnIdleCache() {
        var clock = new AtomicTicker();
        var threadNames = new ConcurrentLinkedQueue<String>();

        try (Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(1_000)
                .concurrencyLevel(4)
                .expireAfterWrite(Duration.ofSeconds(10))
                .ticker(clock)
                .backgroundCleanUp(Duration.ofMillis(10))
                .removalListener((k, v, c) -> threadNames.add(Thread.currentThread().getName()))
                .build()) {
            for (int i = 0; i < 50; i++) {
                cache.put("k" + i, i);
            }
            clock.advance(Duration.ofSeconds(11));

            // From here NOTHING touches the cache. Only the sweeper can empty it.
            awaitCondition(() -> cache.size() == 0, "the sweeper to remove the expired entries");

            assertEquals(50, cache.stats().expirationCount());
            assertEquals(50, threadNames.size());
            assertTrue(threadNames.stream().allMatch(name -> name.startsWith("velox-sweeper-")),
                    "the removals must have been delivered from the sweeper thread: " + threadNames);
        }
    }

    @Test
    @DisplayName("close stops the sweeper, so dead entries stay until something else removes them")
    void closeStopsTheSweeper() throws Exception {
        var clock = new AtomicTicker();
        var cache = (ShardedCache<String, Integer>) CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(100)
                .concurrencyLevel(2)
                .expireAfterWrite(Duration.ofSeconds(10))
                .ticker(clock)
                .backgroundCleanUp(Duration.ofMillis(5))
                .build();
        assertTrue(cache.hasBackgroundCleanUp());
        for (int i = 0; i < 10; i++) {
            cache.put("k" + i, i);
        }

        cache.close();
        cache.close();                                     // closing twice is harmless
        assertFalse(cache.hasBackgroundCleanUp());
        clock.advance(Duration.ofSeconds(11));
        Thread.sleep(300);                                 // plenty of time for a live sweeper to act

        assertEquals(10, cache.size(), "with the sweeper stopped, nothing removes the dead entries");
    }

    @Test
    @DisplayName("a sweep that fails does not stop the schedule")
    void sweeperSurvivesAFailure() {
        // A periodic task that throws is silently cancelled by the executor. The sweeper must
        // catch the failure, or one glitch would end cleanup forever without a sign.
        var clock = new AtomicTicker();
        var armed = new AtomicBoolean();
        var glitchesLeft = new AtomicInteger(3);

        try (Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(100)
                .concurrencyLevel(2)
                .expireAfterWrite(Duration.ofSeconds(10))
                .ticker(() -> {
                    if (armed.get() && glitchesLeft.getAndDecrement() > 0) {
                        throw new IllegalStateException("clock glitch");
                    }
                    return clock.read();
                })
                .backgroundCleanUp(Duration.ofMillis(5))
                .build()) {
            for (int i = 0; i < 20; i++) {
                cache.put("k" + i, i);
            }
            clock.advance(Duration.ofSeconds(11));

            armed.set(true);                               // the next three sweeps fail

            awaitCondition(() -> cache.size() == 0, "a later sweep to succeed after the failures");
            assertTrue(glitchesLeft.get() <= 0, "the failures must actually have happened");
        }
    }

    @Test
    @DisplayName("a cache built without the option has no background thread")
    void noSweeperByDefault() {
        try (Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(10).concurrencyLevel(2).build()) {
            assertFalse(((ShardedCache<String, Integer>) cache).hasBackgroundCleanUp());
        }
    }

    @Test
    @DisplayName("the option needs a thread-safe cache and a positive interval")
    void builderRules() {
        assertThrows(IllegalStateException.class, () -> CacheBuilder.<String, Integer>newBuilder()
                .backgroundCleanUp(Duration.ofSeconds(1)).build(),
                "a background thread cannot safely touch a single-threaded cache");
        assertThrows(IllegalArgumentException.class, () -> CacheBuilder.<String, Integer>newBuilder()
                .backgroundCleanUp(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CacheBuilder.<String, Integer>newBuilder()
                .backgroundCleanUp(Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("a single-threaded cache can be closed too, and it does nothing")
    void closingAPlainCacheIsHarmless() {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder().maximumSize(10).build();
        cache.put("A", 1);

        cache.close();

        assertEquals(1, cache.getIfPresent("A"));
    }
}
