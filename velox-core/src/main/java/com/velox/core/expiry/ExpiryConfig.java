package com.velox.core.expiry;

/**
 * How entries expire, as configured on the cache.
 *
 * <h2>The two kinds of TTL</h2>
 *
 * <ul>
 *   <li><b>Expire after write</b> -- an entry lives a fixed time after it was
 *       stored, however often it is read. This bounds <i>staleness</i>: if the
 *       database row can change, this is the longest a wrong value can be
 *       served. It is the one to reach for first.</li>
 *   <li><b>Expire after access</b> -- the clock restarts on every read, so only
 *       <i>idle</i> entries expire. This bounds <i>memory</i> held by unused
 *       data, but on its own a popular entry never expires and can go
 *       arbitrarily stale.</li>
 * </ul>
 *
 * Both may be set together. The entry then expires at the <b>earlier</b> of the
 * two: reading keeps it alive, but never past the write-based limit.
 *
 * <h2>Jitter</h2>
 *
 * If ten thousand entries are loaded in the same second with the same TTL, they
 * all expire in the same second, and the database absorbs ten thousand misses
 * at once -- a <b>cache avalanche</b>. Jitter randomises each TTL by up to
 * &plusmn;{@code jitter} (0.15 means &plusmn;15%), turning one cliff into a
 * gentle slope. The mean TTL is unchanged.
 *
 * @param expireAfterWriteNanos  write TTL in nanoseconds, or -1 for none
 * @param expireAfterAccessNanos access TTL in nanoseconds, or -1 for none
 * @param jitter                 fraction in [0, 1) by which each TTL varies
 */
public record ExpiryConfig(long expireAfterWriteNanos, long expireAfterAccessNanos, double jitter) {

    /** No expiry configured. Per-entry TTLs passed to {@code put} still work. */
    public static final ExpiryConfig NONE = new ExpiryConfig(-1, -1, 0);

    public ExpiryConfig {
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("jitter must be in [0, 1), got " + jitter);
        }
    }

    /** @return whether a write-based TTL is configured */
    public boolean hasWriteTtl() {
        return expireAfterWriteNanos >= 0;
    }

    /** @return whether an access-based TTL is configured */
    public boolean hasAccessTtl() {
        return expireAfterAccessNanos >= 0;
    }
}
