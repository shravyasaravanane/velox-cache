package com.velox.core.policy;

import com.velox.core.Cache;
import com.velox.core.CacheBuilder;
import com.velox.core.VeloxCache;
import com.velox.core.structure.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LfuPolicyTest {

    private static VeloxCache<String, Integer> cache(int capacity) {
        return new VeloxCache<>(capacity, new LfuPolicy<>());
    }

    // ------------------------------------------------------------------
    //  Core behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("evicts the entry with the fewest uses")
    void evictsLeastFrequentlyUsed() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.getIfPresent("A");
        cache.getIfPresent("A");   // A: 3 uses
        cache.getIfPresent("B");   // B: 2 uses, C: 1 use

        cache.put("D", 4);

        assertNull(cache.getIfPresent("C"), "C has the lowest frequency and must be evicted");
        assertNotNull(cache.getIfPresent("A"));
        assertNotNull(cache.getIfPresent("B"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("ties on frequency are broken by evicting the least recently used")
    void tieBreakIsLeastRecentlyUsed() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);   // all frequency 1; A is the oldest

        cache.put("D", 4);

        assertNull(cache.getIfPresent("A"), "A is the LRU among the tied entries");
        assertNotNull(cache.getIfPresent("B"));
        assertNotNull(cache.getIfPresent("C"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("frequency beats recency: the case where LFU wins and LRU loses")
    void frequencyBeatsRecency() {
        var lfu = cache(2);
        var lru = new VeloxCache<String, Integer>(2, new LruPolicy<>());

        for (var c : java.util.List.of(lfu, lru)) {
            c.put("X", 1);
            for (int i = 0; i < 5; i++) {
                c.getIfPresent("X");     // X is genuinely popular
            }
            c.put("Y", 2);               // a stranger arrives, more recently than X's last use
            c.put("Z", 3);               // room is needed
        }

        assertNotNull(lfu.getIfPresent("X"), "LFU knows X is popular and keeps it");
        assertNull(lru.getIfPresent("X"), "LRU only sees that Y is more recent, and drops X");
    }

    @Test
    @DisplayName("overwriting a key counts as a use")
    void overwriteCountsAsUse() {
        var cache = cache(2);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.put("A", 99);   // A now has frequency 2
        cache.put("C", 3);    // B (frequency 1) must be the victim

        assertNull(cache.getIfPresent("B"));
        assertEquals(99, cache.getIfPresent("A"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("LFU's weakness: a newcomer is the next victim before it can build a count")
    void newcomersAreImmediatelyVulnerable() {
        // Documenting a genuine flaw, not a bug. Every new entry starts at
        // frequency 1, which is the lowest possible, so it is the first
        // thing evicted when the next newcomer arrives. In a cache full of
        // established entries, new keys thrash through a single slot.
        var cache = cache(3);
        for (String k : new String[]{"A", "B", "C"}) {
            cache.put(k, 1);
            for (int i = 0; i < 5; i++) {
                cache.getIfPresent(k);
            }
        }

        cache.put("D", 4);   // evicts one of A/B/C (all tied), D enters at frequency 1
        cache.put("E", 5);   // D is now the lowest frequency, so D is evicted

        assertNull(cache.getIfPresent("D"), "D was evicted the moment E arrived");
        assertNotNull(cache.getIfPresent("E"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("invalidating an entry removes it from its frequency bucket")
    void invalidateCleansBuckets() {
        var cache = cache(5);
        for (int i = 0; i < 5; i++) {
            cache.put("k" + i, i);
            for (int j = 0; j < i; j++) {
                cache.getIfPresent("k" + i);   // give each a distinct frequency
            }
        }
        cache.assertInvariants();

        for (int i = 0; i < 5; i++) {
            cache.invalidate("k" + i);
            cache.assertInvariants();          // empty buckets must not linger in the chain
        }

        assertEquals(0, cache.size());
    }

    // ------------------------------------------------------------------
    //  Configuration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("names distinguish the aged variant")
    void names() {
        assertEquals("LFU", new LfuPolicy<String, Integer>().name());
        assertEquals("LFU-AGED", new LfuPolicy<String, Integer>(100).name());
    }

    @Test
    @DisplayName("a negative aging period is rejected")
    void rejectsNegativeAgingPeriod() {
        assertThrows(IllegalArgumentException.class, () -> new LfuPolicy<String, Integer>(-1));
    }

    // ------------------------------------------------------------------
    //  Aging
    // ------------------------------------------------------------------

    @Test
    @DisplayName("aging halves every frequency once per period")
    void agingHalvesFrequencies() {
        var policy = new LfuPolicy<String, Integer>(10);
        var node = new Node<>("A", 1, 1);
        policy.onInsert(node);
        assertEquals(1, node.frequency());

        for (int i = 0; i < 9; i++) {
            policy.onAccess(node);           // requests 1..9: frequency climbs to 10
        }
        assertEquals(10, node.frequency());

        policy.onAccess(node);               // request 10: frequency 11, then aging fires
        assertEquals(5, node.frequency(), "11 halved is 5");
        policy.assertInvariants(1);
    }

    @Test
    @DisplayName("aging never drives a frequency below 1")
    void agingHasAFloorOfOne() {
        var policy = new LfuPolicy<String, Integer>(2);
        var node = new Node<>("A", 1, 1);
        policy.onInsert(node);

        for (int i = 0; i < 50; i++) {
            policy.onMiss("ghost");          // nothing but aging events
        }

        assertEquals(1, node.frequency());
        policy.assertInvariants(1);
    }

    @Test
    @DisplayName("misses count towards aging, so a stale hot set can be displaced")
    void agingRepairsAShiftedHotSet() {
        // The scenario aging exists for. Phase 1: ten keys become very hot.
        // Phase 2: the workload moves to ten different keys. Plain LFU keeps
        // the old keys forever on the strength of their historical counts,
        // leaving the new keys to thrash through the one remaining slot.
        double plain = shiftedPhaseTwoHitRate(Policy.LFU);
        double aged = shiftedPhaseTwoHitRate(Policy.LFU_AGED);

        System.out.printf("  [LFU aging] after the hot set moves: plain LFU %.1f%%, aged LFU %.1f%%%n",
                plain * 100, aged * 100);

        assertTrue(plain < 0.05, "plain LFU should be stuck on the stale hot set; got " + plain);
        assertTrue(aged > plain + 0.3,
                "aging should let the new hot set take over; plain " + plain + ", aged " + aged);
    }

    private static double shiftedPhaseTwoHitRate(Policy policy) {
        Cache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(10)
                .policy(policy)
                .build();

        for (int round = 0; round < 100; round++) {          // phase 1: keys 0-9 are hot
            for (int k = 0; k < 10; k++) {
                if (cache.getIfPresent(k) == null) {
                    cache.put(k, k);
                }
            }
        }

        long before = cache.stats().hitCount();
        long requestsBefore = cache.stats().requestCount();

        for (int round = 0; round < 300; round++) {          // phase 2: keys 100-109 are hot
            for (int k = 100; k < 110; k++) {
                if (cache.getIfPresent(k) == null) {
                    cache.put(k, k);
                }
            }
        }

        long hits = cache.stats().hitCount() - before;
        long requests = cache.stats().requestCount() - requestsBefore;
        return (double) hits / requests;
    }

    // ------------------------------------------------------------------
    //  Differential tests against a brute-force LFU
    // ------------------------------------------------------------------

    @Test
    @DisplayName("matches a brute-force LFU exactly over 150,000 random operations")
    void differentialNoAging() {
        var stats = PolicyTestSupport.runDifferential(
                "LFU", cap -> new LfuPolicy<>(), () -> new PolicyTestSupport.NaiveLfu(0),
                40, 150, 150_000, 33L);
        System.out.printf("  [LFU differential] identical to brute force. %s%n", stats);
    }

    @Test
    @DisplayName("matches a brute-force LFU with very frequent aging")
    void differentialFrequentAging() {
        // A tiny period forces the bucket-merge logic in age() to run
        // thousands of times, with entries constantly colliding into shared buckets.
        var stats = PolicyTestSupport.runDifferential(
                "LFU aging=25", cap -> new LfuPolicy<>(25), () -> new PolicyTestSupport.NaiveLfu(25),
                40, 150, 150_000, 44L);
        System.out.printf("  [LFU differential, aging every 25] identical. %s%n", stats);
    }

    @Test
    @DisplayName("matches a brute-force LFU with occasional aging")
    void differentialOccasionalAging() {
        PolicyTestSupport.runDifferential(
                "LFU aging=400", cap -> new LfuPolicy<>(400), () -> new PolicyTestSupport.NaiveLfu(400),
                40, 150, 150_000, 55L);
    }
}
