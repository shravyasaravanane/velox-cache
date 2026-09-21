package com.velox.core.policy;

import com.velox.core.Cache;
import com.velox.core.CacheBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A miniature preview of the Tier 4 benchmark lab: race every policy over
 * several workloads and compare hit rates.
 *
 * <p>The point is the <i>shape</i> of the results. No policy wins everywhere,
 * and that fact is the whole reason this project has more than one policy.
 */
class PolicyComparisonTest {

    private static final Policy[] POLICIES = {
            Policy.LRU, Policy.FIFO, Policy.RANDOM, Policy.CLOCK, Policy.LFU, Policy.LFU_AGED
    };

    // ------------------------------------------------------------------
    //  Workload generators
    // ------------------------------------------------------------------

    /** Keys 0..loopSize-1 in order, repeated. With loopSize = capacity + 1, the LRU killer. */
    private static int[] loop(int loopSize, int repeats) {
        int[] requests = new int[loopSize * repeats];
        for (int i = 0; i < requests.length; i++) {
            requests[i] = i % loopSize;
        }
        return requests;
    }

    /**
     * Zipfian keys: rank i is requested with probability proportional to
     * 1 / (i+1)^theta. Real web traffic looks like this -- a few keys get
     * most requests. Sampled by binary search over the cumulative weights.
     */
    private static int[] zipf(Random random, int keySpace, double theta, int count, int offset) {
        double[] cumulative = new double[keySpace];
        double total = 0;
        for (int i = 0; i < keySpace; i++) {
            total += 1.0 / Math.pow(i + 1, theta);
            cumulative[i] = total;
        }
        int[] requests = new int[count];
        for (int i = 0; i < count; i++) {
            int index = Arrays.binarySearch(cumulative, random.nextDouble() * total);
            requests[i] = offset + (index >= 0 ? index : -index - 1);
        }
        return requests;
    }

    /** A Zipfian hot set whose keys are completely replaced twice: 3 phases. */
    private static int[] hotSetShift(Random random) {
        int[] all = new int[300_000];
        for (int phase = 0; phase < 3; phase++) {
            int[] part = zipf(random, 1000, 0.99, 100_000, phase * 1000);
            System.arraycopy(part, 0, all, phase * 100_000, 100_000);
        }
        return all;
    }

    /**
     * A small hot set mixed with a long sequential scan of never-repeated keys.
     * 30% of requests hit one of 50 hot keys; 70% walk through cold keys once.
     * This is a nightly batch job or a crawler running beside real traffic.
     */
    private static int[] scanPollution(Random random) {
        int[] requests = new int[200_000];
        int scanCursor = 10_000;
        for (int i = 0; i < requests.length; i++) {
            requests[i] = random.nextDouble() < 0.30 ? random.nextInt(50) : scanCursor++;
        }
        return requests;
    }

    // ------------------------------------------------------------------
    //  Runner
    // ------------------------------------------------------------------

    private static double hitRate(Policy policy, int capacity, int[] requests) {
        Cache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(capacity)
                .policy(policy)
                .build();
        for (int key : requests) {
            if (cache.getIfPresent(key) == null) {
                cache.put(key, key);      // cache-aside: load on miss
            }
        }
        return cache.stats().hitRate();
    }

    private static Map<Policy, Double> race(int capacity, int[] requests) {
        Map<Policy, Double> results = new LinkedHashMap<>();
        for (Policy policy : POLICIES) {
            results.put(policy, hitRate(policy, capacity, requests));
        }
        return results;
    }

    private static void print(String workload, Map<Policy, Double> results) {
        StringBuilder row = new StringBuilder(String.format("  %-22s", workload));
        for (Map.Entry<Policy, Double> e : results.entrySet()) {
            row.append(String.format("%9.1f%%", e.getValue() * 100));
        }
        System.out.println(row);
    }

    // ------------------------------------------------------------------
    //  The race
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no policy wins on every workload")
    void racePoliciesAcrossWorkloads() {
        var random = new Random(2026);

        Map<Policy, Double> tightLoop = race(100, loop(101, 300));
        Map<Policy, Double> steadyZipf = race(250, zipf(random, 5000, 0.99, 300_000, 0));
        Map<Policy, Double> shifting = race(100, hotSetShift(random));
        Map<Policy, Double> polluted = race(100, scanPollution(random));

        StringBuilder header = new StringBuilder(String.format("  %-22s", "hit rate"));
        for (Policy p : POLICIES) {
            header.append(String.format("%10s", p.displayName()));
        }
        System.out.println();
        System.out.println(header);
        print("loop (cap+1 keys)", tightLoop);
        print("steady Zipf 0.99", steadyZipf);
        print("hot set shifts x2", shifting);
        print("hot set + scan flood", polluted);
        System.out.println();

        // --- Facts that should hold, with the reason for each ---

        // A loop one larger than the cache. Every deterministic policy evicts
        // the entry it is about to need, forever. Only Random escapes.
        for (Policy p : new Policy[]{Policy.LRU, Policy.FIFO, Policy.CLOCK, Policy.LFU}) {
            assertEquals(0.0, tightLoop.get(p), 1e-9, p + " should score exactly 0% on the loop");
        }
        // Random only hurts itself when it happens to evict the one key needed
        // next: roughly a 1-in-capacity chance per miss. With capacity 100 that
        // puts the hit rate in the high nineties, so 0.9 is a meaningful bar.
        assertTrue(tightLoop.get(Policy.RANDOM) > 0.9,
                "random has no pattern to be out of phase with; got " + tightLoop.get(Policy.RANDOM));

        // Stable popularity: counting uses is the right idea, so LFU beats recency.
        assertTrue(steadyZipf.get(Policy.LFU) > steadyZipf.get(Policy.LRU),
                "on a stable skewed workload LFU should beat LRU");

        // Moving popularity: LFU never lets go of the old hot set; aging fixes that.
        assertTrue(shifting.get(Policy.LFU_AGED) > shifting.get(Policy.LFU),
                "aging should help when the hot set moves");

        // A scan floods LRU with one-time keys that push the real hot keys out.
        // LFU's counts protect them.
        assertTrue(polluted.get(Policy.LFU) > polluted.get(Policy.LRU) + 0.05,
                "frequency counts should shield the hot set from the scan");
    }
}
