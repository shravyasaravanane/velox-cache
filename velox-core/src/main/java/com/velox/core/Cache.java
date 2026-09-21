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
public interface Cache<K, V> {

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
     * @return the number of cached entries
     * @implNote O(1)
     */
    int size();

    /**
     * @return the maximum number of entries this cache will hold
     * @implNote O(1)
     */
    int maximumSize();

    /**
     * @return an immutable snapshot of the counters at this instant
     * @implNote O(1)
     */
    CacheStats stats();
}
