package com.velox.core.policy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Belady's algorithm</b> (also called MIN, or OPT): the eviction rule that is
 * mathematically <b>optimal</b> for a fixed cache size — proven, in 1966, to produce the
 * fewest possible misses over any given request sequence. Every policy in this project is a
 * heuristic guess at what OPT already knows for certain.
 *
 * <h2>The rule, and why nothing here can actually run it</h2>
 *
 * When room is needed, evict whichever resident entry is <b>needed furthest in the
 * future</b> — or, if it is never asked for again, evict that one first of all. No real
 * cache can do this: it would have to know every future request before serving the current
 * one. That is exactly why {@link EvictionPolicy} has no method for it — this class does not
 * implement that interface at all, because there is no {@code onAccess}-shaped hook that
 * could receive knowledge of the future.
 *
 * <h2>What this class is for instead</h2>
 *
 * A <b>ceiling</b>, computed offline. Given a full trace up front, {@link #simulate} replays
 * it once and reports the hit rate no online policy — not ARC, not W-TinyLFU, nothing — can
 * ever beat on that exact trace. Reporting "SLRU gets 78%" means little on its own; reporting
 * "SLRU gets 78% of a possible 91%" says exactly how much headroom is left and whether a
 * fancier policy is worth building at all. This is the number Tier 4's benchmark reports
 * measure every real policy against.
 *
 * <h2>The algorithm</h2>
 *
 * <ol>
 *   <li>Walk the trace <b>backwards</b> once, recording each request's <i>next</i>
 *       occurrence index (or {@link Integer#MAX_VALUE} if it never recurs). O(n).</li>
 *   <li>Walk it <b>forwards</b>, keeping the resident set in an {@link IndexedMaxHeap} keyed
 *       by "when is this next needed". A hit updates that key's entry to its new next-use
 *       index (further away now that this use is spent). A miss with a full cache evicts
 *       whoever is on top of the heap — the furthest-away next use, exactly Belady's rule —
 *       admits the new key unconditionally (it was just asked for, this instant), and
 *       records its own next-use index. O(log n) per step, O(n log n) overall.</li>
 * </ol>
 *
 * @see IndexedMaxHeap the structure that makes step 2 O(log n) instead of an O(n) scan
 */
public final class BeladyOracle {

    private BeladyOracle() {
    }

    /**
     * @param hits     lookups the optimal policy would have served from cache
     * @param misses   lookups the optimal policy would still have had to fetch
     * @param requests the total lookups simulated ({@code hits + misses})
     */
    public record Result(long hits, long misses, long requests) {

        /** @return the fraction of lookups served from cache, in {@code [0, 1]}; {@code 1.0} for an empty trace */
        public double hitRate() {
            return requests == 0 ? 1.0 : (double) hits / requests;
        }
    }

    /**
     * Simulates the optimal offline policy over {@code requests} at the given capacity.
     *
     * @param requests the full sequence of keys that will be looked up, in order
     * @param capacity how many entries the cache may hold; at least 1
     * @param <K>      the key type
     * @return the resulting hit/miss counts
     * @implNote O(n log n): one linear backward pass, then one forward pass doing a
     *           heap operation per request.
     */
    public static <K> Result simulate(List<K> requests, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        int n = requests.size();
        int[] nextUse = computeNextUseIndices(requests);

        Map<K, Boolean> resident = new HashMap<>();
        IndexedMaxHeap<K> byFarthestNextUse = new IndexedMaxHeap<>();
        long hits = 0;

        for (int i = 0; i < n; i++) {
            K key = requests.get(i);
            if (resident.containsKey(key)) {
                hits++;
                byFarthestNextUse.update(key, nextUse[i]);
            } else {
                if (resident.size() >= capacity) {
                    K victim = byFarthestNextUse.removeMax();
                    resident.remove(victim);
                }
                resident.put(key, Boolean.TRUE);
                byFarthestNextUse.insert(key, nextUse[i]);
            }
        }

        return new Result(hits, n - hits, n);
    }

    /**
     * @return {@code index[i]} = the smallest {@code j > i} with {@code requests.get(j).equals(requests.get(i))},
     *         or {@link Integer#MAX_VALUE} if {@code requests.get(i)} never recurs after {@code i}
     */
    private static <K> int[] computeNextUseIndices(List<K> requests) {
        int n = requests.size();
        int[] nextUse = new int[n];
        Map<K, Integer> mostRecentIndexSeen = new HashMap<>();
        for (int i = n - 1; i >= 0; i--) {
            K key = requests.get(i);
            Integer next = mostRecentIndexSeen.get(key);
            nextUse[i] = next != null ? next : Integer.MAX_VALUE;
            mostRecentIndexSeen.put(key, i);
        }
        return nextUse;
    }
}
