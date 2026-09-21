package com.velox.core.expiry;

/**
 * The available expiry-scheduling strategies.
 *
 * <p>Both are exact; they differ in cost and in memory.
 */
public enum ExpiryEngineType {

    /**
     * An indexed binary min-heap. O(log n) to schedule, reschedule and cancel;
     * O(1) to find the next entry due. Compact and simple, and the better choice
     * for small caches or when entries are rarely rescheduled.
     */
    INDEXED_HEAP,

    /**
     * A hierarchical timing wheel. O(1) to schedule, reschedule and cancel. The
     * better choice for large caches, and especially with {@code expireAfterAccess},
     * which reschedules an entry on every hit.
     *
     * <p>Measured (informally, one laptop -- see {@code ExpiryEngineComparison}):
     * reschedule cost tied with the heap up to about 10,000 entries, then the
     * wheel pulled ahead: 1.5x faster at 100,000 entries and 1.7x at 1,000,000.
     * Below roughly 100,000 entries the heap was cheaper over an entry's whole
     * lifecycle, because the wheel pays for the passage of time (the hand must
     * sweep) while the heap pays only per entry. Treat the crossover as
     * "somewhere around 100,000 entries" until Tier 4 measures it properly.
     */
    TIMING_WHEEL
}
