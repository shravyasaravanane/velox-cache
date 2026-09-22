package com.velox.core.policy;

import com.velox.core.VeloxCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeladyOracleTest {

    @Test
    @DisplayName("capacity at least as large as the key space: only the initial fill ever misses")
    void noEvictionsNeededMeansOnlyTheFirstPassMisses() {
        List<String> requests = List.of("A", "B", "C", "A", "B", "C");

        var result = BeladyOracle.simulate(requests, 3);

        assertEquals(3, result.hits());
        assertEquals(3, result.misses());
        assertEquals(0.5, result.hitRate());
    }

    @Test
    @DisplayName("a hand-worked example: repeating A,B,C through a 2-slot cache")
    void handWorkedExample() {
        // Backward pass gives next-use indices [3,4,5,6,7,8,MAX,MAX,MAX] for A,B,C,A,B,C,A,B,C.
        // With only 2 slots, OPT always evicts whichever of the two residents is needed
        // furthest away -- worked through by hand in BeladyOracleTest's history, this
        // sequence yields exactly 3 hits (at the three positions unaffected by whichever
        // way the two genuine ties in this trace happen to be broken, since every tied
        // candidate at those points is needed "never again" either way).
        List<String> requests = List.of("A", "B", "C", "A", "B", "C", "A", "B", "C");

        var result = BeladyOracle.simulate(requests, 2);

        assertEquals(3, result.hits());
        assertEquals(6, result.misses());
        assertEquals(9, result.requests());
    }

    @Test
    @DisplayName("a key requested only once never produces a hit")
    void allDistinctKeysNeverHit() {
        List<Integer> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            requests.add(i);
        }

        var result = BeladyOracle.simulate(requests, 10);

        assertEquals(0, result.hits());
        assertEquals(100, result.misses());
    }

    @Test
    @DisplayName("an empty trace has a hit rate of 1.0, by convention")
    void emptyTraceHitRateIsOne() {
        var result = BeladyOracle.simulate(List.of(), 5);

        assertEquals(0, result.hits());
        assertEquals(0, result.misses());
        assertEquals(1.0, result.hitRate());
    }

    @Test
    @DisplayName("capacity must be at least 1")
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> BeladyOracle.simulate(List.of("A"), 0));
        assertThrows(IllegalArgumentException.class, () -> BeladyOracle.simulate(List.of("A"), -1));
    }

    @Test
    @DisplayName("no online policy can beat the oracle's hit rate on the same trace")
    void oracleIsTheCeilingForEveryOnlinePolicy() {
        int keySpace = 50;
        int capacity = 10;
        int length = 20_000;
        var random = new Random(99);
        List<Integer> trace = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            // Skewed towards small keys, the same technique PolicyTestSupport uses: a few
            // keys dominate, as in real traffic, so the ceiling is meaningfully below 100%.
            trace.add((int) (keySpace * Math.pow(random.nextDouble(), 2.0)));
        }

        double oracleHitRate = BeladyOracle.simulate(trace, capacity).hitRate();
        assertTrue(oracleHitRate > 0 && oracleHitRate < 1.0, "the trace should be neither trivially easy nor impossible");

        for (Policy policy : Policy.values()) {
            var cache = new VeloxCache<Integer, Integer>(capacity, policy.create(capacity));
            long hits = 0;
            for (Integer key : trace) {
                if (cache.getIfPresent(key) != null) {
                    hits++;
                } else {
                    cache.put(key, key);
                }
            }
            double realHitRate = (double) hits / length;
            assertTrue(realHitRate <= oracleHitRate + 1e-9,
                    policy + " scored a hit rate of " + realHitRate + ", above the oracle's ceiling of "
                            + oracleHitRate);
        }
    }
}
