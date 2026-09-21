package com.velox.core;

import com.velox.core.expiry.AtomicTicker;
import com.velox.core.policy.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-thread stress tests for {@link ShardedCache}.
 *
 * <p>Concurrency bugs are the kind that pass a hundred runs and fail the hundred and
 * first, so these tests are built to make wrong behaviour <i>detectable</i> rather than
 * merely likely to crash: they check exact invariants (accounting, statistics, capacity)
 * at the end of a hostile run, not just "no exception".
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ShardedCacheStressTest {

    private static final int THREADS = 16;

    /** A value with an identity and a weight, so every put is a distinct thing to account for. */
    private static final class Val {
        final int id;
        final int weight;

        Val(int id, int weight) {
            this.id = id;
            this.weight = weight;
        }
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
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

    // ------------------------------------------------------------------
    //  The conservation law, under concurrency
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("16 threads, exclusive reads (the default): every value is resident OR reported exactly once")
    void conservationUnderConcurrency(Policy policy) throws Exception {
        conservation(policy, false);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("16 threads, buffered reads (opt-in): every value is resident OR reported exactly once")
    void conservationUnderConcurrencyBufferedReads(Policy policy) throws Exception {
        conservation(policy, true);
    }

    private void conservation(Policy policy, boolean bufferedReads) throws Exception {
        final int opsPerThread = 25_000;
        final int keySpace = 300;
        final long budget = 600;

        var clock = new AtomicTicker();
        var nextId = new AtomicInteger();
        var reported = new AtomicIntegerArray(THREADS * opsPerThread + 16);
        var causeCounts = new AtomicLongArray(RemovalCause.values().length);
        var holder = new AtomicReference<ShardedCache<String, Val>>();
        var budgetViolations = new AtomicInteger();

        Cache<String, Val> built = CacheBuilder.<String, Val>newBuilder()
                .maximumWeight(budget)
                .weigher((k, v) -> v.weight)
                .concurrencyLevel(8)
                .policy(policy)
                .expireAfterWrite(Duration.ofNanos(60_000))
                .ticker(clock)
                .bufferedReads(bufferedReads)
                .readBufferSize(16)                     // tiny, so drains and drops happen constantly
                .removalListener((key, val, cause) -> {
                    reported.incrementAndGet(val.id);
                    causeCounts.incrementAndGet(cause.ordinal());
                    if (val.id % 7 == 0) {
                        holder.get().containsKey(key);  // a listener re-entering the cache must not deadlock
                    }
                })
                .build();
        var cache = (ShardedCache<String, Val>) built;
        holder.set(cache);

        var start = new CountDownLatch(1);
        var running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS + 2);
        List<List<Val>> created = new ArrayList<>();

        try {
            // A thread that keeps moving time forward, so entries really do expire mid-run.
            Future<?> clockThread = pool.submit(() -> {
                var random = new Random(1);
                while (running.get()) {
                    clock.advanceNanos(random.nextInt(20_000));
                    LockSupport.parkNanos(50_000);
                }
                return null;
            });
            // A thread that watches the budget while everything else is moving.
            Future<?> monitor = pool.submit(() -> {
                while (running.get()) {
                    // Each shard is within its share at every instant, so a sum of instantaneous
                    // per-shard readings can never exceed the total budget.
                    if (cache.weightedSize() > budget) {
                        budgetViolations.incrementAndGet();
                    }
                    Thread.yield();
                }
                return null;
            });

            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int seed = t;
                var mine = new ArrayList<Val>();
                created.add(mine);
                workers.add(pool.submit(() -> {
                    start.await();
                    var random = new Random(seed * 7919L);
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "k" + (int) (keySpace * Math.pow(random.nextDouble(), 2.0));
                        int action = random.nextInt(100);

                        if (action < 35) {
                            cache.getIfPresent(key);
                        } else if (action < 60) {
                            var val = newVal(nextId, random);
                            mine.add(val);
                            cache.put(key, val);
                        } else if (action < 70) {
                            var val = newVal(nextId, random);
                            mine.add(val);
                            cache.put(key, val, Duration.ofNanos(10_000 + random.nextInt(100_000)));
                        } else if (action < 85) {
                            cache.get(key, k -> {
                                var val = newVal(nextId, random);
                                mine.add(val);
                                return val;
                            });
                        } else if (action < 93) {
                            cache.invalidate(key);
                        } else if (action < 99) {
                            cache.getIfPresent(key);
                        } else if (random.nextInt(50) == 0) {
                            cache.invalidateAll();
                        } else {
                            cache.cleanUp();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(150, TimeUnit.SECONDS);      // rethrows anything a worker threw
            }
            running.set(false);
            clockThread.get(10, TimeUnit.SECONDS);
            monitor.get(10, TimeUnit.SECONDS);
        } finally {
            running.set(false);
            pool.shutdownNow();
        }

        // ---- quiescent: now the exact accounting ----
        cache.cleanUp();
        cache.assertInvariants();
        assertEquals(0, budgetViolations.get(), policy + ": the weight budget was exceeded while running");
        assertTrue(cache.weightedSize() <= budget);

        Set<Integer> resident = new HashSet<>();
        for (int i = 0; i < keySpace; i++) {
            Val val = cache.getIfPresent("k" + i);
            if (val != null) {
                resident.add(val.id);
            }
        }

        int total = 0;
        for (List<Val> list : created) {
            for (Val val : list) {
                total++;
                int accounted = reported.get(val.id) + (resident.contains(val.id) ? 1 : 0);
                assertEquals(1, accounted, policy + ": value " + val.id + " was reported " + reported.get(val.id)
                        + " time(s) and " + (resident.contains(val.id) ? "is" : "is not")
                        + " resident; it must be exactly one of those");
            }
        }

        var stats = cache.stats();
        assertEquals(stats.evictionCount() + stats.rejectionCount(), causeCounts.get(RemovalCause.SIZE.ordinal()),
                policy + ": SIZE reports must equal evictions plus rejections");
        assertEquals(stats.expirationCount(), causeCounts.get(RemovalCause.EXPIRED.ordinal()),
                policy + ": EXPIRED reports must equal the expiration count");
        assertTrue(causeCounts.get(RemovalCause.EXPIRED.ordinal()) > 0, "the run never expired anything");
        assertTrue(causeCounts.get(RemovalCause.SIZE.ordinal()) > 0, "the run never evicted anything");
        System.out.printf("  [stress %-8s] %d values stored, all accounted for; dropped reads: %d%n",
                policy, total, cache.droppedReads());
    }

    private static Val newVal(AtomicInteger nextId, Random random) {
        // Mostly small; now and then one heavier than a shard's whole share, to force refusals.
        int weight = random.nextInt(30) == 0 ? 90 + random.nextInt(40) : 1 + random.nextInt(9);
        return new Val(nextId.getAndIncrement(), weight);
    }

    // ------------------------------------------------------------------
    //  Statistics stay exact under contention
    // ------------------------------------------------------------------

    @Test
    @DisplayName("read-heavy load: hit and miss counts are EXACT, and the cache is intact afterwards")
    void readHeavyStatisticsAreExact() throws Exception {
        final int opsPerThread = 60_000;
        var cache = (ShardedCache<Integer, Integer>) CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(2_000)
                .concurrencyLevel(8)
                .bufferedReads(true)
                .readBufferSize(16)
                .build();
        for (int i = 0; i < 500; i++) {
            cache.put(i, i);                               // a hot set that fits: nearly every read is a hit
        }

        var hitsSeen = new AtomicLong();
        var missesSeen = new AtomicLong();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int seed = t;
                workers.add(pool.submit(() -> {
                    start.await();
                    var random = new Random(seed);
                    long hits = 0;
                    long misses = 0;
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = random.nextInt(600);     // ~17% of keys were never stored: guaranteed misses
                        if (random.nextInt(100) == 0) {
                            cache.put(key, i);              // occasional writes contend with the readers
                        }
                        if (cache.getIfPresent(key) != null) {
                            hits++;
                        } else {
                            misses++;
                        }
                    }
                    hitsSeen.addAndGet(hits);
                    missesSeen.addAndGet(misses);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        cache.assertInvariants();
        var stats = cache.stats();
        assertEquals(hitsSeen.get(), stats.hitCount(),
                "the threads saw " + hitsSeen.get() + " hits but the cache counted " + stats.hitCount());
        assertEquals(missesSeen.get(), stats.missCount());
        assertEquals((long) THREADS * opsPerThread, stats.requestCount());
        System.out.printf("  [read-heavy] %d lookups: hit rate %.1f%%, %d read records dropped (%.2f%%)%n",
                stats.requestCount(), stats.hitRate() * 100, cache.droppedReads(),
                100.0 * cache.droppedReads() / Math.max(1, stats.hitCount()));
    }

    // ------------------------------------------------------------------
    //  Stampede, on the real thread-safe path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("300 threads asking a thread-safe cache for one cold key run the loader exactly once")
    void stampedeThroughTheCache() throws Exception {
        final int callers = 300;
        var cache = (ShardedCache<String, Integer>) CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(100).concurrencyLevel(4).build();
        var loaderRuns = new AtomicInteger();
        var release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);

        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> cache.get("hot", key -> {
                    loaderRuns.incrementAndGet();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return 42;
                })));
            }
            awaitCondition(() -> cache.stats().coalescedCount() == callers - 1, "every follower to be waiting");
            assertEquals(1, loaderRuns.get());
            release.countDown();
            for (Future<Integer> result : results) {
                assertEquals(42, result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, loaderRuns.get(), "300 callers, ONE load");
        assertEquals(42, cache.getIfPresent("hot"));
        assertEquals(callers - 1, cache.stats().coalescedCount());
    }

    // ------------------------------------------------------------------
    //  Writers are not starved by a stream of readers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a steady stream of hits cannot starve writers")
    void writersAreNotStarved() throws Exception {
        var cache = (ShardedCache<Integer, Integer>) CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(1_000).concurrencyLevel(2).bufferedReads(true).readBufferSize(16).build();
        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
        }
        var running = new AtomicBoolean(true);
        var writesDone = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        try {
            for (int t = 0; t < THREADS - 1; t++) {
                pool.submit(() -> {
                    var random = new Random();
                    while (running.get()) {
                        cache.getIfPresent(random.nextInt(100));      // relentless hits
                    }
                    return null;
                });
            }
            Future<?> writer = pool.submit(() -> {
                for (int i = 0; i < 20_000; i++) {
                    cache.put(1_000 + (i % 400), i);
                    writesDone.incrementAndGet();
                }
                return null;
            });

            writer.get(60, TimeUnit.SECONDS);                          // would time out if starved
        } finally {
            running.set(false);
            pool.shutdownNow();
        }

        assertEquals(20_000, writesDone.get());
        cache.assertInvariants();
    }
}
