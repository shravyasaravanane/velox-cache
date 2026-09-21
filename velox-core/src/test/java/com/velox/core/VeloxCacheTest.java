package com.velox.core;

import com.velox.core.policy.LruPolicy;
import com.velox.core.policy.Policy;
import com.velox.core.stats.CacheStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VeloxCache} with the LRU policy.
 */
class VeloxCacheTest {

    private static VeloxCache<String, Integer> cache(int maximumSize) {
        return new VeloxCache<>(maximumSize, new LruPolicy<>());
    }

    // ------------------------------------------------------------------
    //  The assignment's core behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stores and retrieves a value")
    void putThenGet() {
        var cache = cache(3);

        cache.put("A", 1);

        assertEquals(1, cache.getIfPresent("A"));
        assertEquals(1, cache.size());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a lookup for an absent key returns null and counts as a miss")
    void getAbsentKey() {
        var cache = cache(3);

        assertNull(cache.getIfPresent("ghost"));

        assertEquals(1, cache.stats().missCount());
        assertEquals(0, cache.stats().hitCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("never exceeds its maximum size")
    void neverExceedsCapacity() {
        var cache = cache(3);

        for (int i = 0; i < 100; i++) {
            cache.put("k" + i, i);
            assertTrue(cache.size() <= 3, "size blew past the limit at insert " + i);
            cache.assertInvariants();
        }

        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("evicts the least recently used entry when full")
    void evictsLeastRecentlyUsed() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.put("D", 4);   // full -> A is the oldest, so A goes

        assertNull(cache.getIfPresent("A"), "A was least recently used and should be gone");
        assertEquals(2, cache.getIfPresent("B"));
        assertEquals(3, cache.getIfPresent("C"));
        assertEquals(4, cache.getIfPresent("D"));
        assertEquals(1, cache.stats().evictionCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("reading an entry rescues it from eviction")
    void readingRescuesFromEviction() {
        // This is the heart of LRU and the point of the whole exercise.
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.getIfPresent("A");   // A is now the MOST recently used
        cache.put("D", 4);         // so B, not A, is the oldest

        assertEquals(1, cache.getIfPresent("A"), "A was read, so it must have survived");
        assertNull(cache.getIfPresent("B"), "B became the oldest and should be evicted");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("reproduces the hand-traced example from the learning guide")
    void reproducesWorkedExample() {
        // docs/LEARN-01-FOUNDATIONS.md section 5, at the cache level this time.
        var policy = new LruPolicy<String, Integer>();
        var cache = new VeloxCache<>(3, policy);

        cache.put("A", 1);
        assertEquals(List.of("A"), policy.keysFromMruToLru());

        cache.put("B", 2);
        assertEquals(List.of("B", "A"), policy.keysFromMruToLru());

        cache.put("C", 3);
        assertEquals(List.of("C", "B", "A"), policy.keysFromMruToLru());

        cache.getIfPresent("A");
        assertEquals(List.of("A", "C", "B"), policy.keysFromMruToLru());

        cache.put("D", 4);
        assertEquals(List.of("D", "A", "C"), policy.keysFromMruToLru());

        assertNull(cache.getIfPresent("B"), "B was evicted at the last step");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("overwriting an existing key updates it without evicting anything")
    void putExistingKeyUpdatesInPlace() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.put("A", 99);   // A already exists — no room is needed

        assertEquals(99, cache.getIfPresent("A"));
        assertEquals(3, cache.size(), "an overwrite must not change the entry count");
        assertEquals(0, cache.stats().evictionCount(), "an overwrite must not evict anything");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("overwriting an existing key counts as using it")
    void putExistingKeyCountsAsUse() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.put("A", 99);   // A becomes most recently used
        cache.put("D", 4);    // so B is the oldest and gets evicted

        assertNotNull(cache.getIfPresent("A"));
        assertNull(cache.getIfPresent("B"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a cache of size 1 keeps only the newest entry")
    void capacityOfOne() {
        var cache = cache(1);

        cache.put("A", 1);
        cache.put("B", 2);

        assertNull(cache.getIfPresent("A"));
        assertEquals(2, cache.getIfPresent("B"));
        assertEquals(1, cache.size());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a maximum size below 1 is rejected")
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> cache(0));
        assertThrows(IllegalArgumentException.class, () -> cache(-5));
    }

    // ------------------------------------------------------------------
    //  Removal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalidate removes an entry and frees its slot")
    void invalidateRemovesEntry() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.invalidate("A");

        assertNull(cache.getIfPresent("A"));
        assertEquals(1, cache.size());
        assertEquals(0, cache.stats().evictionCount(),
                "a manual removal is not an eviction — it says nothing about memory pressure");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("invalidating an absent key is harmless")
    void invalidateAbsentKey() {
        var cache = cache(3);
        cache.put("A", 1);

        cache.invalidate("ghost");

        assertEquals(1, cache.size());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("invalidateAll empties the cache and the policy together")
    void invalidateAllEmptiesEverything() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.invalidateAll();

        assertEquals(0, cache.size());
        assertNull(cache.getIfPresent("A"));
        // assertInvariants compares the map's count against the policy's, so
        // this also proves clear() did not leave the policy holding entries.
        cache.assertInvariants();
    }

    @Test
    @DisplayName("containsKey does not count as using an entry")
    void containsKeyDoesNotAffectRecency() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        assertTrue(cache.containsKey("A"));   // must NOT rescue A
        cache.put("D", 4);

        assertNull(cache.getIfPresent("A"), "containsKey should not have promoted A");
        assertEquals(0, cache.stats().hitCount(), "containsKey is not a lookup");
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Loader
    // ------------------------------------------------------------------

    @Test
    @DisplayName("get() runs the loader on a miss and caches the result")
    void getWithLoaderFillsMisses() {
        var cache = cache(3);
        var loaderCalls = new ArrayList<String>();

        int first = cache.get("A", key -> {
            loaderCalls.add(key);
            return 42;
        });
        int second = cache.get("A", key -> {
            loaderCalls.add(key);
            return 42;
        });

        assertEquals(42, first);
        assertEquals(42, second);
        assertEquals(List.of("A"), loaderCalls, "the loader must run only on the miss");
        assertEquals(1, cache.stats().loadCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a loader returning null caches nothing")
    void loaderReturningNullCachesNothing() {
        var cache = cache(3);

        assertNull(cache.get("A", key -> null));

        assertEquals(0, cache.size(), "null must not be stored");
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Statistics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hit and miss counts produce the right hit rate")
    void tracksHitRate() {
        var cache = cache(10);
        cache.put("A", 1);

        cache.getIfPresent("A");      // hit
        cache.getIfPresent("A");      // hit
        cache.getIfPresent("A");      // hit
        cache.getIfPresent("ghost");  // miss

        CacheStats stats = cache.stats();
        assertEquals(3, stats.hitCount());
        assertEquals(1, stats.missCount());
        assertEquals(4, stats.requestCount());
        assertEquals(0.75, stats.hitRate(), 1e-9);
        assertEquals(0.25, stats.missRate(), 1e-9);
    }

    @Test
    @DisplayName("stats() snapshots do not change after they are taken")
    void statsSnapshotsAreImmutable() {
        var cache = cache(10);
        cache.put("A", 1);
        cache.getIfPresent("A");

        CacheStats before = cache.stats();
        cache.getIfPresent("A");
        CacheStats after = cache.stats();

        assertEquals(1, before.hitCount(), "the old snapshot must not have moved");
        assertEquals(2, after.hitCount());
        assertEquals(1, after.minus(before).hitCount(), "one hit happened in between");
    }

    // ------------------------------------------------------------------
    //  THE FINDING: LRU's scan failure, as an executable demonstration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("LRU scores exactly 0% on a loop one larger than the cache")
    void lruScoresZeroOnLoopingScan() {
        // This is the pathological case from docs/LEARN-01-FOUNDATIONS.md,
        // turned into a test. It is not checking for a bug — the cache is
        // working perfectly. It is documenting a REAL LIMITATION of the LRU
        // algorithm itself, which is the entire motivation for Tier 3.
        //
        // Capacity 3, requests 1,2,3,4,1,2,3,4,... Every key is evicted on
        // the request immediately before it is needed again. Forever.
        var cache = cache(3);
        int keys = 4;          // exactly one more than the cache can hold
        int loops = 50;

        for (int loop = 0; loop < loops; loop++) {
            for (int key = 0; key < keys; key++) {
                String k = "k" + key;
                if (cache.getIfPresent(k) == null) {
                    cache.put(k, key);
                }
            }
        }

        CacheStats stats = cache.stats();
        assertEquals(keys * loops, stats.requestCount());
        assertEquals(0, stats.hitCount(),
                "LRU cannot get a single hit on this workload — that is the point");
        assertEquals(0.0, stats.hitRate(), 1e-9);

        System.out.printf("  [LRU scan failure] %d requests, %d hits, hit rate %.1f%%%n",
                stats.requestCount(), stats.hitCount(), stats.hitRate() * 100);

        // And the contrast: a policy that simply REFUSED to admit the fourth
        // key would keep k0, k1, k2 and score 75%. LRU cannot do that, because
        // it has no admission control — it accepts every newcomer
        // unconditionally. That missing capability is what W-TinyLFU adds.
    }

    @Test
    @DisplayName("LRU does well when the hot set fits in the cache")
    void lruPerformsWellOnRepeatedAccess() {
        // The fair counterweight to the test above: when the working set fits,
        // LRU is excellent. It is a good default, not a bad algorithm.
        var cache = cache(10);
        var random = new Random(7);

        for (int i = 0; i < 10_000; i++) {
            String key = "hot" + random.nextInt(8);   // 8 keys, capacity 10
            if (cache.getIfPresent(key) == null) {
                cache.put(key, i);
            }
        }

        CacheStats stats = cache.stats();
        assertTrue(stats.hitRate() > 0.99,
                "the whole working set fits, so almost everything should hit; got "
                        + stats.hitRate());

        System.out.printf("  [LRU hot set] %d requests, hit rate %.2f%%%n",
                stats.requestCount(), stats.hitRate() * 100);
    }

    // ------------------------------------------------------------------
    //  The differential test — against Java's own LRU
    // ------------------------------------------------------------------

    @Test
    @DisplayName("matches java.util.LinkedHashMap's LRU behaviour exactly over 100,000 operations")
    void behavesIdenticallyToLinkedHashMapLru() {
        // Java ships an LRU cache: LinkedHashMap in access-order mode with
        // removeEldestEntry overridden. It is the canonical, obviously-correct
        // reference implementation.
        //
        // So rather than trusting our hand-written version, we run both on the
        // same random operations and demand they agree on EVERY answer and,
        // crucially, on exactly which keys survive. If our eviction order were
        // wrong by even one entry, the surviving key sets would diverge and
        // this test would say so immediately.
        final int capacity = 50;

        var ours = cache(capacity);

        Map<String, Integer> reference = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                return size() > capacity;
            }
        };

        var random = new Random(20260921);
        int keySpace = 120;   // more keys than capacity, so eviction happens constantly

        for (int step = 0; step < 100_000; step++) {
            String key = "key" + random.nextInt(keySpace);
            int action = random.nextInt(10);

            if (action < 6) {
                // GET (60%)
                Integer oursValue = ours.getIfPresent(key);
                Integer referenceValue = reference.get(key);
                assertEquals(referenceValue, oursValue,
                        "step " + step + ": disagreed on the value of " + key);

            } else if (action < 9) {
                // PUT (30%)
                int value = random.nextInt(1_000_000);
                ours.put(key, value);
                reference.put(key, value);

            } else {
                // INVALIDATE (10%)
                ours.invalidate(key);
                reference.remove(key);
            }

            assertEquals(reference.size(), ours.size(), "size diverged at step " + step);

            // The decisive check: not just how many entries survived, but
            // WHICH ones. This is what proves our eviction order is right.
            if (step % 500 == 0) {
                assertEquals(sorted(reference.keySet()), sortedCacheKeys(ours, keySpace),
                        "the surviving key sets diverged at step " + step);
                ours.assertInvariants();
            }
        }

        ours.assertInvariants();
        assertEquals(sorted(reference.keySet()), sortedCacheKeys(ours, keySpace),
                "the final surviving key sets diverged");

        System.out.printf("  [differential] 100,000 ops vs LinkedHashMap: identical. %s%n",
                ours.stats());
    }

    private static Set<String> sorted(Set<String> keys) {
        return new TreeSet<>(keys);
    }

    /**
     * Collects the cache's live keys by probing, using {@code containsKey} so
     * we do not disturb the recency order while inspecting it.
     */
    private static Set<String> sortedCacheKeys(VeloxCache<String, Integer> cache, int keySpace) {
        var present = new TreeSet<String>();
        for (int i = 0; i < keySpace; i++) {
            String key = "key" + i;
            if (cache.containsKey(key)) {
                present.add(key);
            }
        }
        return present;
    }

    // ------------------------------------------------------------------
    //  Builder
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the builder produces a working cache")
    void builderProducesWorkingCache() {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(2)
                .policy(Policy.LRU)
                .build();

        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        assertEquals(2, cache.size());
        assertNull(cache.getIfPresent("A"));
        assertEquals(2, cache.maximumSize());
    }

    @Test
    @DisplayName("the builder rejects a maximum size below 1")
    void builderRejectsInvalidSize() {
        assertThrows(IllegalArgumentException.class,
                () -> CacheBuilder.newBuilder().maximumSize(0));
    }

    @Test
    @DisplayName("an empty cache reports a hit rate of 1.0 rather than dividing by zero")
    void emptyCacheHitRate() {
        var cache = cache(3);

        assertEquals(1.0, cache.stats().hitRate(), 1e-9);
        assertEquals(0, cache.stats().requestCount());
        assertFalse(cache.containsKey("anything"));
    }
}
