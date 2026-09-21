package com.velox.core.stats;

/**
 * An immutable snapshot of a cache's counters.
 *
 * <h2>Why hit rate is the number that matters</h2>
 *
 * Everything this project does — all ten eviction policies, the sketches,
 * the benchmark lab — exists to move {@link #hitRate()} upward. It is worth
 * understanding why a few percentage points justify that much work.
 *
 * <p>With a 25ms database and a 0.1ms cache:
 *
 * <pre>
 *   hit rate    average latency    DB queries per 1000 requests
 *      0%           25.0 ms                  1000
 *     50%           12.6 ms                   500
 *     90%            2.6 ms                   100
 *     99%            0.35 ms                   10
 * </pre>
 *
 * <p>Going from 90% to 99% is only nine percentage points, but it removes
 * <b>90% of the remaining database load</b>. The relationship is not linear:
 * what matters is the miss rate, and going 90% → 99% cuts misses tenfold.
 *
 * <p>This is a {@code record}, so it is immutable: calling
 * {@code cache.stats()} twice gives two independent snapshots that will not
 * change underneath you. That matters once several threads are reporting
 * metrics concurrently.
 *
 * @param hitCount       requests served from cache
 * @param missCount      requests that found nothing
 * @param evictionCount  entries dropped to make room (not manual removals)
 * @param loadCount      times a loader ran to fill a miss
 * @param rejectionCount candidates an admission policy refused to cache
 */
public record CacheStats(
        long hitCount,
        long missCount,
        long evictionCount,
        long loadCount,
        long rejectionCount) {

    /** A snapshot with every counter at zero. */
    public static final CacheStats EMPTY = new CacheStats(0, 0, 0, 0, 0);

    /** @return total lookups, hits plus misses */
    public long requestCount() {
        return hitCount + missCount;
    }

    /**
     * @return the fraction of lookups served from cache, in {@code [0, 1]};
     *         {@code 1.0} for a cache that has never been asked anything
     */
    public double hitRate() {
        long requests = requestCount();
        return requests == 0 ? 1.0 : (double) hitCount / requests;
    }

    /** @return the fraction of lookups that missed, in {@code [0, 1]} */
    public double missRate() {
        return 1.0 - hitRate();
    }

    /**
     * Returns the difference between this snapshot and an earlier one.
     *
     * <p>Counters only ever increase, so a raw snapshot describes the cache's
     * whole lifetime. To chart a <i>live</i> hit rate you want the rate over
     * the last second, which means subtracting the previous snapshot. The
     * dashboard in Tier 6 is built on exactly this.
     *
     * @param earlier a snapshot taken before this one
     * @return the activity that happened in between
     */
    public CacheStats minus(CacheStats earlier) {
        return new CacheStats(
                Math.max(0, hitCount - earlier.hitCount),
                Math.max(0, missCount - earlier.missCount),
                Math.max(0, evictionCount - earlier.evictionCount),
                Math.max(0, loadCount - earlier.loadCount),
                Math.max(0, rejectionCount - earlier.rejectionCount));
    }

    @Override
    public String toString() {
        return String.format(
                "CacheStats{hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d, loads=%d, rejections=%d}",
                hitCount, missCount, hitRate() * 100, evictionCount, loadCount, rejectionCount);
    }
}
