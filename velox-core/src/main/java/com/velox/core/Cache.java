package com.velox.core;

import com.velox.core.stats.CacheStats;

import java.util.function.Function;

/**
 * A bounded, in-memory cache.
 *
 * <p>A cache is a map with one extra rule: <b>it is allowed to forget.</b>
 * When it runs out of room it discards an entry to make space, choosing the
 * victim with an {@link com.velox.core.policy.EvictionPolicy}. That single
 * difference is what makes a cache useful — it can sit in front of a huge,
 * slow database while using a small, fixed amount of memory.
 *
 * <p>Because a cache may forget at any time, callers must treat a miss as
 * normal, never as an error. The correct pattern is always:
 *
 * <pre>{@code
 * Product p = cache.getIfPresent(id);
 * if (p == null) {              // a miss is expected, not exceptional
 *     p = database.load(id);
 *     cache.put(id, p);
 * }
 * }</pre>
 *
 * <p>That pattern is called <b>cache-aside</b>, and {@link #get(Object,
 * Function)} does it for you in one call.
 *
 * <h2>The source of truth</h2>
 *
 * A cache is never authoritative. The database is. Everything a cache holds
 * is a disposable copy, which is precisely why it is safe to evict entries,
 * lose them on restart, or let replicas disagree briefly. Keep that in mind
 * as the project grows — it is the reason many of our later trade-offs are
 * acceptable.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface Cache<K, V> extends AutoCloseable {

    /**
     * Returns the cached value for {@code key}, or {@code null} if absent.
     *
     * <p>A hit marks the entry as recently used, which may change what gets
     * evicted next. So this method reads <i>and</i> writes — remember that,
     * it becomes the central problem of Tier 2.
     *
     * @param key the key to look up
     * @return the cached value, or {@code null} on a miss
     * @implNote O(1) average
     */
    V getIfPresent(K key);

    /**
     * Returns the cached value, computing and storing it if absent.
     *
     * <p>This is the cache-aside pattern in one call: on a miss the loader
     * runs, its result is cached, and the value is returned.
     *
     * @param key    the key to look up
     * @param loader computes the value on a miss; may return {@code null}
     * @return the cached or freshly loaded value
     * @implNote O(1) average plus the cost of the loader.
     */
    V get(K key, Function<? super K, ? extends V> loader);

    /**
     * Stores {@code value} under {@code key}, evicting another entry if the
     * cache is full.
     *
     * @param key   the key
     * @param value the value
     * @implNote Amortised O(1)
     */
    void put(K key, V value);

    /**
     * Stores {@code value} with its own time-to-live, overriding any
     * configured expire-after-write for this entry.
     *
     * <p>The deadline is a <b>hard</b> limit: reads can never extend it, even
     * when expire-after-access is configured. A later plain {@link #put(Object,
     * Object)} of the same key replaces it with the cache's configured TTL.
     *
     * @param key   the key
     * @param value the value
     * @param ttl   how long the entry may live; must be positive
     * @throws IllegalArgumentException if {@code ttl} is zero or negative
     * @implNote O(log n) with the heap expiry engine
     */
    void put(K key, V value, java.time.Duration ttl);

    /**
     * Removes every entry whose time-to-live has run out.
     *
     * <p>Expired entries are removed on their own in three ways: lazily when
     * read, opportunistically whenever a {@code put} runs, and here on demand.
     * Call this when you want {@link #size()} to be exact, or to release memory
     * held by expired entries on a cache that is not being written to.
     *
     * @implNote O(k log n) for k expired entries with the heap engine
     */
    void cleanUp();

    /**
     * Removes the entry for {@code key}, if present.
     *
     * @param key the key to drop
     * @implNote O(1) average
     */
    void invalidate(K key);

    /**
     * Removes every entry.
     *
     * @implNote O(n)
     */
    void invalidateAll();

    /**
     * @param key the key to test
     * @return whether an entry exists, <b>without</b> counting as a use
     * @implNote O(1) average
     */
    boolean containsKey(K key);

    /**
     * @return the number of entries held, <b>including entries that have
     *         expired but not yet been removed</b>; call {@link #cleanUp()}
     *         first for an exact count
     * @implNote O(1). Deliberately not exact: making it exact would mean
     *           sweeping the expiry structure on every call.
     */
    int size();

    /**
     * @return the maximum number of entries, if the cache is bounded by entry
     *         count; or <b>-1</b> if it is bounded by weight, where the number of
     *         entries is not fixed. See {@link #maximumWeight()}.
     * @implNote O(1)
     */
    int maximumSize();

    /**
     * @return the capacity budget. For a weight-bounded cache this is the
     *         maximum total weight; for a count-bounded cache every entry weighs
     *         1, so it equals the maximum number of entries
     * @implNote O(1)
     */
    long maximumWeight();

    /**
     * @return the total weight of the entries currently held (equal to
     *         {@link #size()} for a count-bounded cache). Like {@link #size()}, it
     *         includes entries that have expired but not yet been swept
     * @implNote O(1) -- maintained incrementally, never recomputed
     */
    long weightedSize();

    /**
     * @return an immutable snapshot of the counters at this instant
     * @implNote O(1)
     */
    CacheStats stats();

    /**
     * Releases any background resources, such as the cleanup thread of a cache built with
     * {@code backgroundCleanUp}. A cache with none has nothing to release, so the default
     * does nothing; the entries themselves are simply garbage collected with the cache.
     * Safe to call more than once.
     */
    @Override
    default void close() {
    }
}
