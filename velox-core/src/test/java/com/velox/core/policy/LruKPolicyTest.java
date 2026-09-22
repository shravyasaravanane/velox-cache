package com.velox.core.policy;

import com.velox.core.VeloxCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A cache only asks its policy for a victim once it is full and a NEW key arrives -- the
 * {@code (capacity + 1)}-th distinct key put into it. Every test sizes its {@code put}s
 * around that rule so the eviction it checks actually happens where expected.
 */
class LruKPolicyTest {

    @Test
    @DisplayName("among entries never referenced K times, the one referenced longest ago is evicted first")
    void unprovenEntriesAreEvictedOldestFirst() {
        var cache = new VeloxCache<String, Integer>(2, new LruKPolicy<>(2));
        cache.put("A", 1);          // referenced once, at logical time 1
        cache.put("B", 2);          // referenced once, at logical time 2

        cache.put("C", 3);          // 3rd distinct key: forces an eviction

        assertNull(cache.getIfPresent("A"), "A is the older of the two unproven entries");
        assertNotNull(cache.getIfPresent("B"));
        assertNotNull(cache.getIfPresent("C"));
    }

    @Test
    @DisplayName("an entry proven by K references is protected, even ahead of an unproven entry "
            + "referenced more recently")
    void provenEntryOutranksUnprovenEntry() {
        var policy = new LruKPolicy<String, Integer>(2);
        var cache = new VeloxCache<String, Integer>(3, policy);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.getIfPresent("A");    // A: referenced twice now -- proven. B: still referenced once.
        cache.put("C", 3);          // cache full (3 entries); no eviction yet

        cache.put("D", 4);          // 4th distinct key: forces an eviction

        assertNull(cache.getIfPresent("B"), "B, still unproven, must be evicted ahead of the proven A");
        assertNotNull(cache.getIfPresent("A"), "A proved itself with a 2nd reference and must survive");
        assertNotNull(cache.getIfPresent("C"));
    }

    @Test
    @DisplayName("once every entry is proven, the one whose K-th reference is oldest is evicted -- plain LRU-K")
    void amongProvenEntriesTheOldestKthReferenceLoses() {
        var cache = new VeloxCache<String, Integer>(2, new LruKPolicy<>(2));
        cache.put("A", 1);
        cache.getIfPresent("A");    // A proven: its earliest surviving reference is the very first tick
        cache.put("B", 2);
        cache.getIfPresent("B");    // B proven: its earliest surviving reference happened later than A's

        cache.put("C", 3);          // 3rd distinct key: forces an eviction between two proven entries

        assertNull(cache.getIfPresent("A"), "A's earliest surviving reference is older than B's");
        assertNotNull(cache.getIfPresent("B"));
    }

    @Test
    @DisplayName("k must be at least 1")
    void rejectsNonPositiveK() {
        assertThrows(IllegalArgumentException.class, () -> new LruKPolicy<String, Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new LruKPolicy<String, Integer>(-1));
    }

    @Test
    @DisplayName("the display name reflects k")
    void nameReflectsK() {
        assertEquals("LRU-2", new LruKPolicy<String, Integer>(2).name());
        assertEquals("LRU-5", new LruKPolicy<String, Integer>(5).name());
    }

    @Test
    @DisplayName("k=1 degenerates to plain recency: a single reference is enough to compete")
    void kOfOneActsLikePlainRecency() {
        var cache = new VeloxCache<String, Integer>(2, new LruKPolicy<>(1));
        cache.put("A", 1);
        cache.put("B", 2);

        cache.getIfPresent("A");    // with k=1, one reference is already "proven": A is now the newest

        cache.put("C", 3);          // 3rd distinct key: B (the least recently touched) must go

        assertNull(cache.getIfPresent("B"));
        assertNotNull(cache.getIfPresent("A"));
        assertNotNull(cache.getIfPresent("C"));
    }

    @Test
    @DisplayName("differential (k=2): agrees with a naive per-key history scan over random load")
    void differentialAgainstNaiveModelK2() {
        int capacity = 30;
        PolicyTestSupport.runDifferential("LRU-2", cap -> new LruKPolicy<>(2),
                () -> new PolicyTestSupport.NaiveLruK(2), capacity, 60, 40_000, 13);
    }

    @Test
    @DisplayName("differential (k=3): agrees with a naive per-key history scan over random load")
    void differentialAgainstNaiveModelK3() {
        int capacity = 30;
        PolicyTestSupport.runDifferential("LRU-3", cap -> new LruKPolicy<>(3),
                () -> new PolicyTestSupport.NaiveLruK(3), capacity, 60, 40_000, 17);
    }
}
