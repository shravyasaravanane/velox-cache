package com.velox.server.api;

import com.velox.core.stats.CacheStats;
import com.velox.server.cache.HotSwappableCache;
import com.velox.server.cache.ProductCacheService;
import com.velox.server.domain.ProductDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Deliberately hostile traffic patterns, fired at the real read path
 * ({@link ProductCacheService#getCacheAside}), so a cache's specific strengths and gaps are
 * demonstrated with real numbers rather than asserted. Every endpoint here reports the
 * {@link CacheStats} delta it caused: the story is always "here is what changed", not just
 * "here is what happened".
 */
@RestController
public class ChaosController {

    private final ProductCacheService productCacheService;
    private final HotSwappableCache<Long, ProductDetails> primaryCache;

    public ChaosController(ProductCacheService productCacheService,
            HotSwappableCache<Long, ProductDetails> primaryCache) {
        this.productCacheService = productCacheService;
        this.primaryCache = primaryCache;
    }

    /**
     * Invalidates {@code key}, then fires {@code n} concurrent reads at it at once -- a cold
     * entry hit by every waiting request the instant it expires, the classic thundering herd.
     * {@link ProductCacheService#getCacheAside}'s single-flight loading means only one of the
     * {@code n} requests should actually reach the database; the rest are coalesced onto its
     * result. Every one of the {@code n} requests still counts as a miss (each independently
     * finds nothing cached the instant it looks) -- {@code loads}, not {@code misses}, is the
     * number that proves the point: it should read 1 regardless of {@code n}, while
     * {@code coalesced} should read {@code n - 1}.
     */
    @PostMapping("/api/chaos/stampede")
    public Report stampede(@RequestParam long key, @RequestParam(defaultValue = "1000") int n) throws InterruptedException {
        primaryCache.invalidate(key);
        CacheStats before = primaryCache.stats();
        fireSimultaneously(n, () -> productCacheService.getCacheAside(key));
        return Report.delta(before, primaryCache.stats());
    }

    /**
     * Reads {@code n} sequential product ids once each -- a one-pass scan, the pattern that
     * defeats plain LRU by evicting the entire working set to make room for data that is never
     * revisited (see {@code docs/benchmarks/RESULTS.md}'s scan workload). Run this against
     * {@code ?cache=lru} versus the default W-TinyLFU-backed primary path and compare hit rates
     * on {@code GET /api/stats} to see the difference directly.
     */
    @PostMapping("/api/chaos/scan")
    public Report scan(@RequestParam(defaultValue = "1") long startId, @RequestParam(defaultValue = "100000") int n)
            throws InterruptedException {
        CacheStats before = primaryCache.stats();
        runConcurrently(n, i -> productCacheService.getCacheAside(startId + i));
        return Report.delta(before, primaryCache.stats());
    }

    /**
     * Drops every entry from the primary cache at once -- a cache-avalanche simulation.
     *
     * <p>A real mass-expiry avalanche is every entry's TTL elapsing within the same instant;
     * this endpoint reaches the same observable end state -- a full cache going empty in one
     * step, and the burst of misses that follows -- by invalidating directly rather than by
     * waiting out a real TTL window. That simplification is disclosed here rather than
     * simulated with a real clock, since the interesting effect is what happens to traffic
     * <i>after</i> the cache empties, not the expiry mechanism itself.
     */
    @PostMapping("/api/chaos/expire-all")
    public Report expireAll() {
        CacheStats before = primaryCache.stats();
        primaryCache.invalidateAll();
        return Report.delta(before, primaryCache.stats());
    }

    /**
     * Requests {@code n} ids guaranteed not to exist. Cache penetration: because
     * {@link ProductCacheService#getCacheAside} never negative-caches a miss, every one of
     * these {@code n} requests reaches the database, whether it is the first or the
     * thousandth request for that same nonexistent id -- an unaddressed gap, disclosed rather
     * than silently worked around; see {@code PROJECT_PLAN.md}'s Tier 5 scope note on negative
     * caching. The response's {@code misses} count should equal {@code n}.
     */
    @PostMapping("/api/chaos/penetrate")
    public Report penetrate(@RequestParam(defaultValue = "10000") int n) throws InterruptedException {
        CacheStats before = primaryCache.stats();
        // Ids far beyond any realistic seeded catalog, so every one is a genuine miss.
        runConcurrently(n, i -> productCacheService.getCacheAside(1_000_000_000L + i));
        return Report.delta(before, primaryCache.stats());
    }

    /**
     * Releases all {@code n} copies of {@code task} at the same instant, on virtual threads --
     * cheap enough that {@code n} in the thousands is fine -- rather than merely submitting them
     * to a small worker pool, where later ones would not start until the leader's load had
     * likely already finished, understating how much a real simultaneous stampede coalesces.
     * Each worker signals it is ready and waits on {@code start}; the caller opens {@code start}
     * only once every worker is already parked there.
     */
    private static void fireSimultaneously(int n, Runnable task) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await(2, TimeUnit.MINUTES);
            start.countDown();
            done.await(2, TimeUnit.MINUTES);
        }
    }

    private static void runConcurrently(int n, java.util.function.IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, Math.max(1, n)));
        CountDownLatch done = new CountDownLatch(n);
        try {
            for (int i = 0; i < n; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        task.accept(index);
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(2, TimeUnit.MINUTES);
        } finally {
            pool.shutdown();
        }
    }

    private static void runConcurrently(int n, Runnable task) throws InterruptedException {
        runConcurrently(n, i -> task.run());
    }

    public record Report(long hits, long misses, long loads, long evictions, long coalesced, double hitRatePercent) {
        static Report delta(CacheStats before, CacheStats after) {
            CacheStats d = after.minus(before);
            return new Report(d.hitCount(), d.missCount(), d.loadCount(), d.evictionCount(), d.coalescedCount(),
                    d.hitRate() * 100.0);
        }
    }
}
