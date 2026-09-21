package com.velox.core.stats;

/**
 * The mutable counters behind {@link CacheStats}.
 *
 * <p>Plain {@code long} fields, incremented without synchronisation. That is
 * correct for now because Tier 0 is single-threaded.
 *
 * <p>It will <b>not</b> stay correct. {@code count++} is really three
 * instructions — read, add, write — so two threads can both read 5, both
 * write 6, and one increment vanishes. Worse, a single shared counter becomes
 * the hottest memory address in the program: every core fights to own that
 * cache line, and the contention can cost more than the cache lookup itself.
 *
 * <p>Tier 2 replaces this with a striped, cache-line-padded counter. Keeping
 * the counters behind this small class is what makes that swap a one-file
 * change instead of a rewrite.
 */
public final class StatsCounter {

    private long hitCount;
    private long missCount;
    private long evictionCount;
    private long loadCount;
    private long rejectionCount;

    /** Records a lookup served from cache. */
    public void recordHit() {
        hitCount++;
    }

    /** Records a lookup that found nothing. */
    public void recordMiss() {
        missCount++;
    }

    /** Records an entry discarded to make room. */
    public void recordEviction() {
        evictionCount++;
    }

    /** Records a loader running to fill a miss. */
    public void recordLoad() {
        loadCount++;
    }

    /** Records a candidate that an admission policy refused. */
    public void recordRejection() {
        rejectionCount++;
    }

    /** Resets every counter to zero. */
    public void reset() {
        hitCount = 0;
        missCount = 0;
        evictionCount = 0;
        loadCount = 0;
        rejectionCount = 0;
    }

    /** @return an immutable snapshot of the counters right now */
    public CacheStats snapshot() {
        return new CacheStats(hitCount, missCount, evictionCount, loadCount, rejectionCount);
    }
}
