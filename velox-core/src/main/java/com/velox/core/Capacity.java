package com.velox.core;

/**
 * How a cache decides it is full: a budget of weight, spent by entries.
 *
 * <h2>One mechanism, two modes</h2>
 *
 * Internally the cache only ever compares a running <i>total weight</i> against
 * a <i>maximum weight</i>. Bounding by entry count is not a separate code path:
 * it is simply the case where every entry weighs 1, so "the total weight is
 * 1,000" and "there are 1,000 entries" say the same thing. Having one
 * mechanism instead of two means the byte-bounded cache and the count-bounded
 * cache cannot quietly behave differently.
 *
 * @param maximumWeight  the budget, in whatever unit the weigher uses
 * @param weigher        assigns each entry its weight; {@code null} means every entry weighs 1
 * @param maximumEntries the entry limit when bounded by count, or -1 when bounded by weight
 * @param sizingHint     roughly how many entries to expect, used to pre-size the hash table
 *                       and to size policies that depend on capacity (such as LFU aging)
 * @param <K>            the key type
 * @param <V>            the value type
 */
public record Capacity<K, V>(long maximumWeight, Weigher<K, V> weigher, int maximumEntries, int sizingHint) {

    public Capacity {
        if (maximumWeight < 1) {
            throw new IllegalArgumentException("maximum capacity must be at least 1, got " + maximumWeight);
        }
        if (sizingHint < 1) {
            throw new IllegalArgumentException("sizingHint must be at least 1, got " + sizingHint);
        }
    }

    /**
     * Bounds the cache to {@code maximumEntries} entries.
     *
     * @param maximumEntries the most entries to hold; at least 1
     * @param <K>            the key type
     * @param <V>            the value type
     * @return a count-bounded capacity
     */
    public static <K, V> Capacity<K, V> entries(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumSize must be at least 1, got " + maximumEntries);
        }
        return new Capacity<>(maximumEntries, null, maximumEntries, maximumEntries);
    }

    /**
     * Bounds the cache to a total weight.
     *
     * @param maximumWeight   the budget; at least 1
     * @param weigher         weighs each entry
     * @param expectedEntries a rough entry count, for pre-sizing; the cache still works if it is wrong
     * @param <K>             the key type
     * @param <V>             the value type
     * @return a weight-bounded capacity
     */
    public static <K, V> Capacity<K, V> weighted(long maximumWeight, Weigher<K, V> weigher, int expectedEntries) {
        if (weigher == null) {
            throw new NullPointerException("weigher");
        }
        return new Capacity<>(maximumWeight, weigher, -1, expectedEntries);
    }

    /** @return whether the cache is bounded by entry count rather than by weight */
    public boolean isCountBounded() {
        return maximumEntries >= 0;
    }
}
