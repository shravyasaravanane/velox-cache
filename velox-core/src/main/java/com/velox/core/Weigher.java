package com.velox.core;

/**
 * Says how much of a cache's capacity an entry uses.
 *
 * <h2>Why entry counts are not enough</h2>
 *
 * "Hold at most 10,000 entries" is only a memory limit if every entry is the
 * same size. A cache of 10,000 thumbnails and a cache of 10,000 PDFs are
 * different animals: the second can be a thousand times larger, and it will
 * happily exhaust the heap while reporting that it is comfortably within its
 * limit. The brief says "memory is limited"; a weigher is how the cache can
 * actually honour that.
 *
 * <p>With a weigher, capacity becomes a <b>budget</b> ("64 MB") and each entry
 * spends part of it:
 *
 * <pre>{@code
 * CacheBuilder.<String, byte[]>newBuilder()
 *         .maximumWeight(64L * 1024 * 1024)
 *         .weigher((key, bytes) -> bytes.length)
 *         .build();
 * }</pre>
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li><b>Deterministic.</b> The same key and value must always weigh the
 *       same. The cache records an entry's weight when it is stored and later
 *       subtracts <i>that recorded number</i> when the entry leaves; if the
 *       weigher were allowed to change its mind, the running total would drift.</li>
 *   <li><b>At least 1.</b> A zero-weight entry could never trigger eviction, so
 *       a cache full of them would grow without bound. Return
 *       {@code Math.max(1, ...)} if a value can legitimately be empty.</li>
 *   <li><b>Cheap.</b> It runs on every {@code put}. Use a length or a stored
 *       size, not a deep traversal.</li>
 * </ul>
 *
 * The unit is yours to choose (bytes, kilobytes, "credits"): the cache only
 * compares the sum against {@code maximumWeight}.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@FunctionalInterface
public interface Weigher<K, V> {

    /**
     * @param key   the entry's key
     * @param value the entry's value
     * @return this entry's weight; must be at least 1 and stable for a given key and value
     */
    int weigh(K key, V value);
}
