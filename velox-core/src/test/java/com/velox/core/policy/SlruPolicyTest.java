package com.velox.core.policy;

import com.velox.core.VeloxCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlruPolicyTest {

    private static VeloxCache<String, Integer> cache(int capacity) {
        return new VeloxCache<>(capacity, new SlruPolicy<>(capacity));
    }

    @Test
    @DisplayName("a new entry starts on probation")
    void newEntryStartsOnProbation() {
        var policy = new SlruPolicy<String, Integer>(10);
        assertEquals(0, policy.protectedKeys().size());

        var cache = new VeloxCache<String, Integer>(10, policy);
        cache.put("A", 1);

        assertEquals(java.util.List.of("A"), policy.probationKeys());
        assertEquals(java.util.List.of(), policy.protectedKeys());
    }

    @Test
    @DisplayName("a second use promotes the entry to protected")
    void secondUsePromotes() {
        var policy = new SlruPolicy<String, Integer>(10);
        var cache = new VeloxCache<String, Integer>(10, policy);
        cache.put("A", 1);

        cache.getIfPresent("A");

        assertEquals(java.util.List.of("A"), policy.protectedKeys());
        assertEquals(java.util.List.of(), policy.probationKeys());
    }

    @Test
    @DisplayName("a scan through probation cannot touch a protected entry")
    void scanCannotEvictProtectedEntries() {
        // The classic LRU failure: a long run of one-hit keys. SLRU's whole point is
        // that a key used twice is safe from it.
        var cache = cache(4);
        cache.put("hot", 1);
        cache.getIfPresent("hot");            // promoted to protected

        for (int i = 0; i < 100; i++) {
            cache.put("scan" + i, i);         // each one used exactly once
        }

        assertNotNull(cache.getIfPresent("hot"), "a twice-used entry must survive a probation-only scan");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("victims come from probation before protected")
    void probationIsEvictedFirst() {
        var cache = cache(2);
        cache.put("A", 1);
        cache.getIfPresent("A");              // A: protected
        cache.put("B", 2);                    // B: probation

        cache.put("C", 3);                    // no room: B (probation) must go, not A

        assertNull(cache.getIfPresent("B"));
        assertNotNull(cache.getIfPresent("A"));
        assertNotNull(cache.getIfPresent("C"));
    }

    @Test
    @DisplayName("promoting into a full protected segment demotes its LRU entry, not out of the cache")
    void demotionMakesRoomInProtected() {
        // protectedCapacity = floor(5 * 0.8) = 4
        var policy = new SlruPolicy<String, Integer>(5);
        var cache = new VeloxCache<String, Integer>(5, policy);
        for (char c = 'A'; c <= 'D'; c++) {
            cache.put(String.valueOf(c), 1);
            cache.getIfPresent(String.valueOf(c));   // A, B, C, D all promoted, in that order
        }
        assertEquals(4, policy.protectedKeys().size());

        cache.put("E", 1);
        cache.getIfPresent("E");              // promoting E must demote A (least recently used)

        assertEquals(java.util.List.of("A"), policy.probationKeys(), "the demoted entry, not evicted");
        assertTrue(policy.protectedKeys().containsAll(java.util.List.of("E", "D", "C", "B")));
        assertNotNull(cache.getIfPresent("A"), "a demoted entry is still IN the cache");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("protectedFraction must be in [0, 1)")
    void rejectsBadFraction() {
        assertThrows(IllegalArgumentException.class, () -> new SlruPolicy<String, Integer>(10, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new SlruPolicy<String, Integer>(10, -0.1));
    }

    @Test
    @DisplayName("differential: agrees with a naive two-list model over random load")
    void differentialAgainstNaiveModel() {
        int capacity = 30;
        PolicyTestSupport.runDifferential("SLRU", cap -> new SlruPolicy<>(cap),
                () -> new PolicyTestSupport.NaiveSlru(capacity), capacity, 60, 40_000, 7);
    }
}
