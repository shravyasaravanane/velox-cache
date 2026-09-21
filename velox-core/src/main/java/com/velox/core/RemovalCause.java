package com.velox.core;

/**
 * Why an entry left the cache.
 *
 * <p>Distinguishing these matters more than it first appears. "The cache is
 * full so something had to go" and "the user deleted this key" look identical
 * from the outside — the entry is gone either way — but they mean opposite
 * things about the health of your system.
 *
 * <p>A rising {@link #SIZE} count means the cache is under memory pressure
 * and a bigger cache would raise the hit rate. A rising {@link #EXPIRED}
 * count means data is timing out, and shorter TTLs would not help. Only
 * {@code SIZE} removals count as evictions in {@link
 * com.velox.core.stats.CacheStats}, because only those reflect capacity
 * pressure — counting manual deletions as evictions would make a healthy
 * cache look starved.
 */
public enum RemovalCause {

    /** The caller removed it explicitly, via {@code invalidate}. */
    EXPLICIT,

    /** A {@code put} overwrote the value for an existing key. */
    REPLACED,

    /** The cache was full and this was the chosen victim. */
    SIZE,

    /**
     * Its time-to-live ran out.
     *
     * <p>Not produced until Tier 1 adds expiry, but named here so the enum is
     * stable and callers can already switch on it exhaustively.
     */
    EXPIRED;

    /**
     * @return whether this removal was forced by the cache rather than
     *         requested by the caller — i.e. whether the entry might still
     *         have been useful
     */
    public boolean wasAutomatic() {
        return this == SIZE || this == EXPIRED;
    }
}
