package com.velox.core;

import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.FakeTicker;
import com.velox.core.expiry.HeapExpiryEngine;
import com.velox.core.policy.LruPolicy;
import com.velox.core.policy.Policy;
import com.velox.core.util.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capacity measured in weight rather than entry count.
 *
 * <p>In most tests here the <b>value is its own weight</b> (a {@code put("A", 4)}
 * stores an entry that weighs 4), which keeps the arithmetic readable.
 */
class VeloxCacheWeightTest {

    private static VeloxCache<String, Integer> weighted(long maximumWeight) {
        return weighted(maximumWeight, Policy.LRU);
    }

    private static VeloxCache<String, Integer> weighted(long maximumWeight, Policy policy) {
        return new VeloxCache<>(
                Capacity.<String, Integer>weighted(maximumWeight, (key, value) -> value, 16),
                policy.create(16), ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>());
    }

    // ------------------------------------------------------------------
    //  Basics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a count-bounded cache reports weight equal to its entry count")
    void countBoundedWeightEqualsSize() {
        var cache = new VeloxCache<String, Integer>(5, new LruPolicy<>());
        cache.put("A", 100);
        cache.put("B", 200);

        assertEquals(2, cache.weightedSize(), "every entry weighs 1 whatever its value is");
        assertEquals(5, cache.maximumWeight());
        assertEquals(5, cache.maximumSize());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a weight-bounded cache tracks the total weight of its entries")
    void tracksTotalWeight() {
        var cache = weighted(10);

        cache.put("A", 4);
        cache.put("B", 3);

        assertEquals(7, cache.weightedSize());
        assertEquals(2, cache.size());
        assertEquals(10, cache.maximumWeight());
        assertEquals(-1, cache.maximumSize(), "there is no fixed entry limit when bounded by weight");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("an entry that exactly fills the remaining space evicts nothing")
    void exactFitEvictsNothing() {
        var cache = weighted(10);
        cache.put("A", 4);
        cache.put("B", 4);

        cache.put("C", 2);

        assertEquals(10, cache.weightedSize());
        assertEquals(3, cache.size());
        assertEquals(0, cache.stats().evictionCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("one unit over the budget forces an eviction")
    void oneOverEvicts() {
        var cache = weighted(10);
        cache.put("A", 4);
        cache.put("B", 4);
        cache.put("C", 2);

        cache.put("D", 1);

        assertNull(cache.getIfPresent("A"), "A is the LRU entry");
        assertEquals(7, cache.weightedSize());
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Eviction is a loop
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one large newcomer evicts as many small entries as it needs, in LRU order")
    void largeEntryEvictsSeveralSmallOnes() {
        var cache = weighted(10);
        cache.put("A", 2);
        cache.put("B", 2);
        cache.put("C", 2);
        cache.put("D", 2);                 // total 8
        cache.getIfPresent("A");           // A is now the MOST recent: order is A, D, C, B

        cache.put("E", 7);                 // needs 7: evict B (->6), C (->4), D (->2)

        assertNotNull(cache.getIfPresent("A"), "A was rescued by the read");
        assertNull(cache.getIfPresent("B"));
        assertNull(cache.getIfPresent("C"));
        assertNull(cache.getIfPresent("D"));
        assertEquals(7, cache.getIfPresent("E"));
        assertEquals(9, cache.weightedSize());
        assertEquals(3, cache.stats().evictionCount(), "three separate evictions for one insert");
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  An entry heavier than the whole cache
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an entry heavier than the whole cache is refused without evicting anything")
    void oversizedEntryIsRefusedAndNothingIsFlushed() {
        // Evicting everything to make room for something that STILL would not fit
        // would destroy the whole working set for no benefit at all.
        var cache = weighted(10);
        cache.put("A", 4);
        cache.put("B", 4);

        cache.put("HUGE", 11);

        assertNull(cache.getIfPresent("HUGE"));
        assertEquals(4, cache.getIfPresent("A"), "existing entries must be untouched");
        assertEquals(4, cache.getIfPresent("B"));
        assertEquals(8, cache.weightedSize());
        assertEquals(0, cache.stats().evictionCount(), "nothing should have been evicted");
        assertEquals(1, cache.stats().rejectionCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("an entry exactly as heavy as the whole cache is accepted, alone")
    void entryFillingTheWholeCache() {
        var cache = weighted(10);
        cache.put("A", 4);
        cache.put("B", 4);

        cache.put("WHOLE", 10);

        assertEquals(10, cache.getIfPresent("WHOLE"));
        assertNull(cache.getIfPresent("A"));
        assertNull(cache.getIfPresent("B"));
        assertEquals(10, cache.weightedSize());
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Overwriting an existing key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("overwriting with the same weight updates in place")
    void overwriteSameWeight() {
        var cache = weighted(10);
        cache.put("A", 4);

        cache.put("A", 4);

        assertEquals(4, cache.weightedSize());
        assertEquals(1, cache.size());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("overwriting with a lighter value gives the difference back")
    void overwriteLighterFreesSpace() {
        var cache = weighted(10);
        cache.put("A", 6);
        cache.put("B", 4);
        assertEquals(10, cache.weightedSize());

        cache.put("A", 2);                 // A shrinks by 4

        assertEquals(6, cache.weightedSize());
        cache.put("C", 4);                 // fits in the space A gave back
        assertNotNull(cache.getIfPresent("B"), "nothing needed to be evicted");
        assertEquals(0, cache.stats().evictionCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("overwriting with a heavier value evicts others to make room")
    void overwriteHeavierEvictsOthers() {
        var cache = weighted(10);
        cache.put("A", 3);
        cache.put("B", 3);
        cache.put("C", 3);

        cache.put("B", 6);                 // B grows by 3; total would be 12

        assertEquals(6, cache.getIfPresent("B"));
        assertNull(cache.getIfPresent("A"), "A was the LRU entry and had to go");
        assertNotNull(cache.getIfPresent("C"));
        assertEquals(9, cache.weightedSize());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("growing the entry that the policy would evict first does not evict it out from under itself")
    void heavierOverwriteOfTheLruEntry() {
        // A is the LRU tail. Growing it needs room, and the policy's first choice
        // of victim is A itself. Removing-then-reinserting sidesteps that tangle.
        var cache = weighted(10);
        cache.put("A", 3);                 // LRU
        cache.put("B", 3);
        cache.put("C", 3);

        cache.put("A", 8);                 // needs 8: B and C must both go

        assertEquals(8, cache.getIfPresent("A"));
        assertNull(cache.getIfPresent("B"));
        assertNull(cache.getIfPresent("C"));
        assertEquals(8, cache.weightedSize());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("growing an entry beyond the whole cache removes the old value rather than serving it stale")
    void overwriteBeyondCapacityDropsTheOldValue() {
        var cache = weighted(10);
        cache.put("A", 3);
        cache.put("B", 3);

        cache.put("A", 11);                // can never fit

        assertNull(cache.getIfPresent("A"), "a stale value must not linger after its replacement was refused");
        assertEquals(3, cache.getIfPresent("B"), "unrelated entries are untouched");
        assertEquals(3, cache.weightedSize());
        assertEquals(1, cache.stats().rejectionCount());
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Every removal path gives its weight back
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalidate and invalidateAll give their weight back")
    void invalidationReleasesWeight() {
        var cache = weighted(10);
        cache.put("A", 4);
        cache.put("B", 3);

        cache.invalidate("A");
        assertEquals(3, cache.weightedSize());
        cache.assertInvariants();

        cache.invalidateAll();
        assertEquals(0, cache.weightedSize());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("expired entries give their weight back, so a dead entry is reaped before a live one is evicted")
    void expiryReleasesWeight() {
        var clock = new FakeTicker();
        var cache = new VeloxCache<String, Integer>(
                Capacity.<String, Integer>weighted(10, (key, value) -> value, 16),
                new LruPolicy<>(), ExpiryConfig.NONE, clock, new HeapExpiryEngine<>());
        cache.put("LIVE", 5);
        cache.put("DEAD", 5, Duration.ofSeconds(3));
        clock.advance(Duration.ofSeconds(4));

        cache.put("NEW", 5);               // needs 5; the expired entry frees exactly that

        assertNotNull(cache.getIfPresent("LIVE"), "the live entry must survive");
        assertEquals(0, cache.stats().evictionCount(), "nothing live should be evicted");
        assertEquals(1, cache.stats().expirationCount());
        assertEquals(10, cache.weightedSize());
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Weigher validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a weigher returning zero or less is rejected and leaves the cache untouched")
    void invalidWeightsAreRejected() {
        var cache = new VeloxCache<String, Integer>(
                Capacity.<String, Integer>weighted(10, (key, value) -> value, 16),
                new LruPolicy<>(), ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>());
        cache.put("A", 4);

        assertThrows(IllegalArgumentException.class, () -> cache.put("ZERO", 0));
        assertThrows(IllegalArgumentException.class, () -> cache.put("NEGATIVE", -3));

        assertEquals(1, cache.size(), "a rejected put must change nothing");
        assertEquals(4, cache.weightedSize());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a weigher that throws propagates and leaves the cache untouched")
    void throwingWeigherLeavesCacheUntouched() {
        var cache = new VeloxCache<String, Integer>(
                Capacity.<String, Integer>weighted(10, (key, value) -> {
                    throw new IllegalStateException("weigher blew up");
                }, 16),
                new LruPolicy<>(), ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>());

        assertThrows(IllegalStateException.class, () -> cache.put("A", 1));

        assertEquals(0, cache.size());
        assertEquals(0, cache.weightedSize());
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Builder
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the builder produces a working weight-bounded cache")
    void builderWeightMode() {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumWeight(10)
                .weigher((key, value) -> value)
                .build();

        cache.put("A", 6);
        cache.put("B", 6);                 // evicts A

        assertNull(cache.getIfPresent("A"));
        assertEquals(6, cache.weightedSize());
        assertEquals(10, cache.maximumWeight());
        assertEquals(-1, cache.maximumSize());
    }

    @Test
    @DisplayName("contradictory or incomplete capacity settings are rejected")
    void builderRejectsBadCombinations() {
        assertThrows(IllegalStateException.class,
                () -> CacheBuilder.<String, Integer>newBuilder().maximumWeight(10).build(),
                "a weight budget needs a weigher");
        assertThrows(IllegalStateException.class,
                () -> CacheBuilder.<String, Integer>newBuilder().weigher((k, v) -> 1).build(),
                "a weigher needs a weight budget");
        assertThrows(IllegalStateException.class,
                () -> CacheBuilder.<String, Integer>newBuilder()
                        .maximumSize(10).maximumWeight(10).weigher((k, v) -> 1).build(),
                "a cache is bounded by count OR by weight, never both");
        assertThrows(IllegalArgumentException.class, () -> CacheBuilder.newBuilder().maximumWeight(0));
        assertThrows(IllegalArgumentException.class, () -> CacheBuilder.newBuilder().expectedEntries(0));
    }

    // ------------------------------------------------------------------
    //  Works with every policy
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("every policy respects the weight budget under random load")
    void everyPolicyRespectsTheBudget(Policy policy) {
        var cache = weighted(60, policy);
        var random = new Random(policy.ordinal() + 7L);

        for (int step = 0; step < 30_000; step++) {
            String key = "k" + random.nextInt(80);
            switch (random.nextInt(4)) {
                case 0, 1 -> {
                    if (cache.getIfPresent(key) == null) {
                        cache.put(key, 1 + random.nextInt(12));
                    }
                }
                case 2 -> cache.put(key, 1 + random.nextInt(12));
                default -> cache.invalidate(key);
            }
            assertTrue(cache.weightedSize() <= 60,
                    policy + " exceeded the budget at step " + step + ": " + cache.weightedSize());
            if (step % 250 == 0) {
                cache.assertInvariants();
            }
        }
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Differential test against a naive weighted LRU
    // ------------------------------------------------------------------

    /**
     * An obviously-correct weighted LRU built from a plain access-ordered
     * LinkedHashMap, written independently and encoding the same rules:
     * in-place update if not heavier, remove-and-reinsert if heavier, refuse
     * anything larger than the whole budget, evict from the LRU end until it fits.
     */
    private static final class NaiveWeightedLru {
        private final long maximumWeight;
        private final Map<String, Integer> map = new LinkedHashMap<>(16, 0.75f, true);
        private long total;

        NaiveWeightedLru(long maximumWeight) {
            this.maximumWeight = maximumWeight;
        }

        Integer get(String key) {
            return map.get(key);                      // access order: this moves it to MRU
        }

        void put(String key, int weight) {           // the value is its own weight
            if (map.containsKey(key)) {
                int old = map.get(key);
                if (weight <= old) {
                    map.put(key, weight);             // in place, and now MRU
                    total += weight - old;
                    return;
                }
                map.remove(key);                      // heavier: remove, then reinsert below
                total -= old;
            }
            if (weight > maximumWeight) {
                return;
            }
            while (total + weight > maximumWeight) {
                Iterator<Map.Entry<String, Integer>> eldest = map.entrySet().iterator();
                Map.Entry<String, Integer> victim = eldest.next();
                total -= victim.getValue();
                eldest.remove();
            }
            map.put(key, weight);
            total += weight;
        }

        void invalidate(String key) {
            Integer old = map.remove(key);
            if (old != null) {
                total -= old;
            }
        }

        int size() {
            return map.size();
        }

        long weight() {
            return total;
        }

        Set<String> keys() {
            return new TreeSet<>(map.keySet());
        }
    }

    @Test
    @DisplayName("matches a naive weighted LRU exactly over 150,000 random operations")
    void differentialAgainstNaiveWeightedLru() {
        var real = weighted(40);
        var naive = new NaiveWeightedLru(40);
        var random = new Random(31415);
        int keySpace = 60;

        for (int step = 0; step < 150_000; step++) {
            String key = "k" + (int) (keySpace * Math.pow(random.nextDouble(), 2.0));
            int action = random.nextInt(10);

            if (action < 5) {
                assertEquals(naive.get(key), real.getIfPresent(key),
                        "step " + step + " disagreed on get(" + key + ")");
            } else if (action < 9) {
                // Mostly small (1..9), but one put in twenty is HUGE (30..54) against a
                // budget of 40: some fit only by evicting many entries, and some exceed
                // the whole budget and must be refused. Without these the randomized
                // comparison would never reach the trickiest rules.
                int weight = random.nextInt(20) == 0 ? 30 + random.nextInt(25) : 1 + random.nextInt(9);
                naive.put(key, weight);
                real.put(key, weight);
            } else {
                naive.invalidate(key);
                real.invalidate(key);
            }

            assertEquals(naive.size(), real.size(), "entry count diverged at step " + step);
            assertEquals(naive.weight(), real.weightedSize(), "total weight diverged at step " + step);

            if (step % 250 == 0) {
                var present = new TreeSet<String>();
                for (int i = 0; i < keySpace; i++) {
                    if (real.containsKey("k" + i)) {
                        present.add("k" + i);
                    }
                }
                assertEquals(naive.keys(), present, "surviving keys diverged at step " + step);
                real.assertInvariants();
            }
        }
        real.assertInvariants();
        assertTrue(real.stats().rejectionCount() > 100,
                "the oversized-entry path was barely exercised: " + real.stats().rejectionCount());
        System.out.printf("  [weighted differential] 150,000 ops identical to naive. %s%n", real.stats());
    }
}
