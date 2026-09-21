package com.velox.core;

import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.FakeTicker;
import com.velox.core.expiry.HeapExpiryEngine;
import com.velox.core.loader.SingleFlight;
import com.velox.core.policy.LruPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The scenario from the foundations guide, run for real: a hot key expires, and
 * hundreds of requests for it arrive in the same instant.
 *
 * <p>Two runs of the identical scenario, side by side:
 * <ul>
 *   <li>plain cache-aside: every request that misses queries the "database";</li>
 *   <li>cache-aside with single-flight: they share one query.</li>
 * </ul>
 *
 * <h2>A note on the lock</h2>
 *
 * {@link VeloxCache} is not thread-safe until Tier 2 adds sharded locking, so
 * here a single lock guards each individual cache call. Crucially the lock is
 * <b>never held while querying the database</b>. That is exactly the structure
 * Tier 2 will have internally, and it is why single-flight lives beside the cache
 * rather than inside its lock: a lock held across a slow query would serialise
 * every request behind it, making the stampede worse instead of better.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StampedeTest {

    private static final int CALLERS = 500;

    private final FakeTicker clock = new FakeTicker();
    private final ReentrantLock cacheLock = new ReentrantLock();
    private final AtomicInteger databaseQueries = new AtomicInteger();

    private VeloxCache<String, Integer> expiredHotKeyCache() {
        var cache = new VeloxCache<String, Integer>(
                100, new LruPolicy<>(),
                new ExpiryConfig(Duration.ofSeconds(60).toNanos(), -1, 0),
                clock, new HeapExpiryEngine<>());
        cache.put("hot", 1);                          // the popular entry...
        clock.advance(Duration.ofSeconds(61));        // ...expires at 12:00:00.000
        return cache;
    }

    private Integer guardedGet(VeloxCache<String, Integer> cache, String key) {
        cacheLock.lock();
        try {
            return cache.getIfPresent(key);
        } finally {
            cacheLock.unlock();
        }
    }

    private void guardedPut(VeloxCache<String, Integer> cache, String key, int value) {
        cacheLock.lock();
        try {
            cache.put(key, value);
        } finally {
            cacheLock.unlock();
        }
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for: " + description);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    //  Without single-flight: the stampede
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WITHOUT single-flight, every concurrent miss queries the database")
    void plainCacheAsideStampedes() throws Exception {
        var cache = expiredHotKeyCache();
        // Every caller must be inside the "database" at once before any of them finishes,
        // which is what genuinely simultaneous misses look like.
        var allInsideDatabase = new CountDownLatch(CALLERS);
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);

        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                results.add(pool.submit(() -> {
                    Integer cached = guardedGet(cache, "hot");
                    if (cached != null) {
                        return cached;
                    }
                    databaseQueries.incrementAndGet();          // the slow query, run by EVERY caller
                    allInsideDatabase.countDown();
                    await(allInsideDatabase);
                    guardedPut(cache, "hot", 2);
                    return 2;
                }));
            }
            for (Future<Integer> result : results) {
                assertEquals(2, result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(CALLERS, databaseQueries.get(), "each of the callers queried the database for the same row");
        System.out.printf("  [stampede] WITHOUT single-flight: %d requests -> %d database queries%n",
                CALLERS, databaseQueries.get());
    }

    // ------------------------------------------------------------------
    //  With single-flight
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WITH single-flight, the same stampede costs exactly one database query")
    void singleFlightCollapsesTheStampede() throws Exception {
        var cache = expiredHotKeyCache();
        var flight = new SingleFlight<String, Integer>();
        var releaseDatabase = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);

        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                results.add(pool.submit(() -> {
                    Integer cached = guardedGet(cache, "hot");
                    if (cached != null) {
                        return cached;
                    }
                    // Note: the cache lock is NOT held here. The load runs unlocked.
                    return flight.load("hot", key -> {
                        databaseQueries.incrementAndGet();
                        await(releaseDatabase);                  // the slow query
                        guardedPut(cache, key, 2);
                        return 2;
                    });
                }));
            }

            // Hold the query open until every other caller is provably waiting on it.
            awaitCondition(() -> flight.coalesced() == CALLERS - 1, "every follower to be waiting");
            assertEquals(1, databaseQueries.get(), "while everyone waits, exactly one query is running");
            releaseDatabase.countDown();

            for (Future<Integer> result : results) {
                assertEquals(2, result.get(30, TimeUnit.SECONDS), "everyone gets the freshly loaded value");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, databaseQueries.get(), "500 concurrent requests, ONE database query");
        assertEquals(CALLERS - 1, flight.coalesced());
        assertEquals(0, flight.inFlightCount());

        // And the cache now holds the fresh value, so later requests need no query at all.
        assertEquals(2, guardedGet(cache, "hot"));
        assertEquals(1, databaseQueries.get());
        System.out.printf("  [stampede] WITH single-flight:    %d requests -> %d database query (%d coalesced)%n",
                CALLERS, databaseQueries.get(), flight.coalesced());
    }
}
