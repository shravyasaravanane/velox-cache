package com.velox.core.stats;

import com.velox.core.concurrent.StripedCounter;

/**
 * The counters behind {@link CacheStats}.
 *
 * <p>Each counter is a {@link StripedCounter}, so any thread may record an event at
 * any time without a lock and without contending on one memory location. That
 * matters for hits in particular: in the sharded cache a hit is recorded on the
 * read path <i>without</i> holding the exclusive lock, by many threads at once, and
 * a single shared {@code long} there would be both a data race and the hottest cache
 * line in the program.
 *
 * <p>The cost is that a snapshot is not one instant: counters are read one after
 * another while others may still be incrementing. Each is individually correct and
 * counters only grow, so a snapshot is always a plausible state, never a corrupt one.
 */
public final class StatsCounter {

    /** Few stripes are enough: a shard's counters are shared only by the threads using that shard. */
    private static final int STRIPES = 4;

    private final StripedCounter hits = new StripedCounter(STRIPES, true);
    private final StripedCounter misses = new StripedCounter(STRIPES, true);
    private final StripedCounter evictions = new StripedCounter(STRIPES, true);
    private final StripedCounter rejections = new StripedCounter(STRIPES, true);
    private final StripedCounter expirations = new StripedCounter(STRIPES, true);

    /** Records a lookup served from cache. */
    public void recordHit() {
        hits.increment();
    }

    /** Records a lookup that found nothing. */
    public void recordMiss() {
        misses.increment();
    }

    /** Records an entry discarded to make room. */
    public void recordEviction() {
        evictions.increment();
    }

    /** Records a candidate that an admission policy refused. */
    public void recordRejection() {
        rejections.increment();
    }

    /** Records an entry removed because its time-to-live ran out. */
    public void recordExpiration() {
        expirations.increment();
    }

    /**
     * Resets every counter to zero.
     *
     * <p>Not atomic with respect to concurrent recording: an event recorded during
     * the reset may or may not survive it. Intended for tests and quiet moments.
     */
    public void reset() {
        for (StripedCounter counter : new StripedCounter[]{hits, misses, evictions, rejections, expirations}) {
            counter.add(-counter.sum());
        }
    }

    /** @return a snapshot of the counters; see the class comment on what "snapshot" means here */
    public CacheStats snapshot() {
        // Loader counters are zero here: the single-flight component owns them,
        // and the cache merges them in with CacheStats.withLoadCounts.
        return new CacheStats(hits.sum(), misses.sum(), evictions.sum(), 0,
                rejections.sum(), expirations.sum(), 0, 0);
    }
}
