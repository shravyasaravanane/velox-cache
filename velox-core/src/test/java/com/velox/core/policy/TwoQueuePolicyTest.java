package com.velox.core.policy;

import com.velox.core.VeloxCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cache only ever asks for a victim when it is full and a NEW key arrives, which is
 * the {@code (capacity + 1)}-th distinct key put into it (an update to an existing key
 * never needs one). Every test below sizes its sequence of {@code put}s around that
 * rule, so the eviction it is checking actually happens where the test expects it.
 */
class TwoQueuePolicyTest {

    @Test
    @DisplayName("a new entry starts in A1in, not Am")
    void newEntryStartsInA1in() {
        var policy = new TwoQueuePolicy<String, Integer>(20);
        var cache = new VeloxCache<String, Integer>(20, policy);

        cache.put("A", 1);

        assertEquals(java.util.List.of("A"), policy.a1inKeys());
        assertEquals(java.util.List.of(), policy.mainKeys());
    }

    @Test
    @DisplayName("a hit while still in A1in does not promote it or reorder it")
    void hitInA1inDoesNothing() {
        // This is the trait that tells 2Q apart from SLRU: a second touch, on its own,
        // is not enough.
        var policy = new TwoQueuePolicy<String, Integer>(20);
        var cache = new VeloxCache<String, Integer>(20, policy);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.getIfPresent("A");
        cache.getIfPresent("A");
        cache.getIfPresent("A");

        assertEquals(java.util.List.of(), policy.mainKeys(), "still not promoted after three hits");
        assertEquals(java.util.List.of("B", "A"), policy.a1inKeys(), "A1in order is untouched by hits");
    }

    @Test
    @DisplayName("an entry evicted from A1in becomes a ghost, and does not disappear entirely")
    void evictedA1inEntryBecomesAGhost() {
        // capacity 2: a1InTarget = 1, so the 3rd distinct key evicts the 1st.
        var policy = new TwoQueuePolicy<String, Integer>(2);
        var cache = new VeloxCache<String, Integer>(2, policy);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.put("C", 3);          // A1in now over its target: A is pushed out

        assertFalse(policy.a1inKeys().contains("A"));
        assertTrue(policy.isGhost("A"), "A must be remembered as a ghost, not simply forgotten");
        assertNull(cache.getIfPresent("A"), "a ghost is still a MISS: only its key is remembered, not its value");
    }

    @Test
    @DisplayName("asking again for a ghost promotes it straight to Am, skipping A1in")
    void ghostHitPromotesStraightToMain() {
        var policy = new TwoQueuePolicy<String, Integer>(2);
        var cache = new VeloxCache<String, Integer>(2, policy);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);          // evicts A from A1in -> ghost
        assertTrue(policy.isGhost("A"));

        cache.put("A", 99);         // referenced again while a ghost

        assertFalse(policy.isGhost("A"), "the ghost is consumed on re-entry");
        assertEquals(java.util.List.of("A"), policy.mainKeys(), "A skips A1in and enters Am directly");
        assertEquals(99, cache.getIfPresent("A"));
    }

    @Test
    @DisplayName("once A1in is at or under its target, Am is evicted from instead -- "
            + "and Am's own evictions do not become ghosts")
    void mainIsEvictedOnceA1inIsAtTarget() {
        var policy = new TwoQueuePolicy<String, Integer>(2);
        var cache = new VeloxCache<String, Integer>(2, policy);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);          // evicts A from A1in -> ghost
        cache.put("A", 99);         // ghost hit: A -> Am; this eviction pass also evicts B -> ghost
        assertEquals(java.util.List.of("A"), policy.mainKeys());
        assertEquals(java.util.List.of("C"), policy.a1inKeys());

        cache.put("D", 4);          // A1in has only 1 entry now (AT its target): Am is evicted instead

        assertNull(cache.getIfPresent("A"), "A must actually be gone");
        assertFalse(policy.isGhost("A"), "an Am eviction is not remembered as a ghost, unlike an A1in eviction");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("Am's own LRU order still governs hits once two entries are inside it")
    void mainBehavesLikeLru() {
        // capacity 5: a1InTarget = 1, a1OutTarget = 2. Promote W then X into Am by the
        // same route as ghostHitPromotesStraightToMain, twice, while keeping A1in large
        // enough (> 1) throughout that neither promotion is itself evicted from Am.
        var policy = new TwoQueuePolicy<String, Integer>(5);
        var cache = new VeloxCache<String, Integer>(5, policy);
        cache.put("W", 1);
        cache.put("X", 1);
        cache.put("Y", 1);
        cache.put("Z", 1);
        cache.put("V", 1);          // cache full (5 entries), all in A1in, none evicted yet
        cache.put("U", 1);          // evicts W (oldest) from A1in -> ghost

        cache.put("W", 2);          // ghost hit: W -> Am; also evicts X (now oldest) from A1in -> ghost
        cache.put("X", 2);          // ghost hit: X -> Am; also evicts Y from A1in -> ghost

        assertEquals(java.util.List.of("X", "W"), policy.mainKeys(), "X was promoted more recently");

        cache.getIfPresent("W");    // W is now the most recently used of Am

        assertEquals(java.util.List.of("W", "X"), policy.mainKeys());
    }

    @Test
    @DisplayName("the ghost list is bounded and forgets its oldest entries")
    void ghostListIsBounded() {
        // capacity 4: a1InTarget = 1, a1OutTarget = 2.
        var policy = new TwoQueuePolicy<String, Integer>(4);
        var cache = new VeloxCache<String, Integer>(4, policy);

        // Push many single-use keys through a 1-slot A1in, minting a ghost each time.
        for (int i = 0; i < 10; i++) {
            cache.put("k" + i, i);
        }

        int ghostCount = 0;
        for (int i = 0; i < 10; i++) {
            if (policy.isGhost("k" + i)) {
                ghostCount++;
            }
        }
        assertTrue(ghostCount <= 2, "the ghost list must stay at or below its target size, saw " + ghostCount);
        cache.assertInvariants();
    }

    @Test
    @DisplayName("an explicit invalidation does not create a ghost")
    void invalidationDoesNotCreateAGhost() {
        var policy = new TwoQueuePolicy<String, Integer>(4);
        var cache = new VeloxCache<String, Integer>(4, policy);
        cache.put("A", 1);

        cache.invalidate("A");

        assertFalse(policy.isGhost("A"), "a deliberate removal is not evidence the eviction policy was wrong");
    }

    @Test
    @DisplayName("fractions must be in range")
    void rejectsBadFractions() {
        assertThrows(IllegalArgumentException.class, () -> new TwoQueuePolicy<String, Integer>(10, 0.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new TwoQueuePolicy<String, Integer>(10, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new TwoQueuePolicy<String, Integer>(10, 0.25, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TwoQueuePolicy<String, Integer>(10, 0.25, -0.1));
    }

    @Test
    @DisplayName("differential: agrees with a naive three-list model over random load")
    void differentialAgainstNaiveModel() {
        int capacity = 30;
        PolicyTestSupport.runDifferential("2Q", cap -> new TwoQueuePolicy<>(cap),
                () -> new PolicyTestSupport.NaiveTwoQueue(capacity), capacity, 60, 40_000, 11);
    }
}
