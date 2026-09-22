package com.velox.core.policy;

import com.velox.core.Capacity;
import com.velox.core.VeloxCache;
import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.HeapExpiryEngine;
import com.velox.core.util.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinyLfuPolicyTest {

    /**
     * A cache whose real entry count can outgrow what {@code policy} was sized for: exactly
     * the condition the promotion duel needs to be reachable at all. See
     * {@link #theDuelIsUnreachableOnAPlainCountBoundedCache()} for why an ordinary
     * {@code new VeloxCache<>(capacity, policy)} can never exercise it.
     */
    private static VeloxCache<String, Integer> weighted(long maximumWeight, EvictionPolicy<String, Integer> policy) {
        return new VeloxCache<>(
                Capacity.<String, Integer>weighted(maximumWeight, (key, value) -> 1, 16),
                policy, ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>());
    }

    @Test
    @DisplayName("a new entry starts in the admission window")
    void newEntryStartsInWindow() {
        var policy = new TinyLfuPolicy<String, Integer>(20);
        var cache = new VeloxCache<String, Integer>(20, policy);

        cache.put("A", 1);

        assertEquals(java.util.List.of("A"), policy.windowKeys());
        assertEquals(java.util.List.of(), policy.probationKeys());
    }

    @Test
    @DisplayName("a scan of one-hit keys cannot dislodge an entry proven onto the protected segment")
    void scanCannotEvictAProtectedEntry() {
        var policy = new TinyLfuPolicy<String, Integer>(10);
        var cache = new VeloxCache<String, Integer>(10, policy);

        for (int i = 0; i < 12; i++) {
            cache.put("filler" + i, i);              // past capacity: the admission cascade is already running
        }
        cache.put("hot", 1);

        int pushes = 0;
        while (!policy.probationKeys().contains("hot") && !policy.protectedKeys().contains("hot")) {
            cache.put("pusher" + pushes, pushes);
            assertTrue(++pushes < 20, "hot should have reached main well before this many further insertions");
        }
        assertNotNull(cache.getIfPresent("hot"), "hot must still be resident once it reaches main");

        cache.getIfPresent("hot");                    // a second use while already in main promotes it
        assertTrue(policy.protectedKeys().contains("hot"), "setup check: hot must now be protected");

        for (int i = 0; i < 200; i++) {
            cache.put("scan" + i, i);                 // each key used exactly once
        }

        assertNotNull(cache.getIfPresent("hot"), "a protected entry must survive a scan of one-hit keys");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("the promotion duel is unreachable on a plain count-bounded cache")
    void theDuelIsUnreachableOnAPlainCountBoundedCache() {
        // Every entry weighs 1, so window.size() + main.size() always equals the cache's
        // actual resident count -- and the cache only ever asks for a victim when that count
        // already equals capacity (== windowCapacity + mainCapacity, by construction). That
        // makes the admission cascade always land EXACTLY on window == windowCapacity and
        // main == mainCapacity, every single time, leaving nothing for a duel to resolve.
        // This test exists to record that finding, not to demonstrate a feature: it simply
        // confirms a long, plain-insertion run never needs a sketch comparison to behave
        // correctly, by running one and checking the cache stays internally consistent.
        var policy = new TinyLfuPolicy<String, Integer>(10);
        var cache = new VeloxCache<String, Integer>(10, policy);

        for (int i = 0; i < 500; i++) {
            cache.put("k" + i, i);
            if (i % 3 == 0) {
                cache.getIfPresent("k" + (i / 2));
            }
        }

        cache.assertInvariants();
        assertTrue(cache.size() <= 10);
    }

    @Test
    @DisplayName("the duel: a tie between two untouched candidates favours the incumbent")
    void duelTieFavoursTheIncumbent() {
        // capacity=10 for the POLICY (windowCapacity=1, mainCapacity=9), but the CACHE's
        // real weight budget allows 15 resident entries: the mismatch a weighted cache can
        // create between "how many entries fit" and "what the policy was sized for" is what
        // makes the duel reachable at all -- see theDuelIsUnreachableOnAPlainCountBoundedCache.
        var policy = new TinyLfuPolicy<String, Integer>(10, 0.01, 0.0);
        var cache = weighted(15, policy);

        for (int i = 1; i <= 15; i++) {
            cache.put("e" + i, i);                    // fills the cache; nothing has been evicted yet
        }
        assertEquals(java.util.List.of(), policy.probationKeys(), "setup check: nothing promoted yet");

        cache.put("trigger", 0);                      // the 16th distinct key: the first-ever eviction

        // The cascade promotes e1..e9 for free (mainCapacity=9), leaving e10..e15 (6 entries)
        // in the window, still 5 over its target of 1. e10, the oldest of those, duels e1,
        // the oldest (and first-promoted) entry in main. Neither has ever been referenced a
        // second time, so both have a sketch estimate of 0 -- a tie, which this policy
        // resolves in favour of the entry already in main.
        assertFalse(cache.containsKey("e10"), "the candidate must lose a tied duel");
        assertTrue(cache.containsKey("e1"), "the incumbent must survive a tied duel");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("the duel: a candidate that has strictly proven itself displaces the incumbent")
    void duelCandidateWinsWithAHigherFrequency() {
        var policy = new TinyLfuPolicy<String, Integer>(10, 0.01, 0.0);
        var cache = weighted(15, policy);

        for (int i = 1; i <= 15; i++) {
            cache.put("e" + i, i);
            if (i == 10) {
                // Boost e10's estimated frequency well above every other key's, WITHOUT
                // disturbing its position: nothing else is inserted while these hits happen,
                // so e10 -- already at the window's head -- simply stays there, exactly as it
                // would with no hits at all. Only its sketch estimate changes.
                for (int hit = 0; hit < 14; hit++) {
                    cache.getIfPresent("e10");
                }
            }
        }
        assertTrue(policy.frequency("e10") > policy.frequency("e1"),
                "setup check: e10 must clearly outscore e1 before the duel happens");

        cache.put("trigger", 0);                      // triggers the same cascade and duel as the tie test

        assertTrue(cache.containsKey("e10"), "the candidate must win a duel it clearly leads");
        assertFalse(cache.containsKey("e1"), "the incumbent it displaced must be the one gone");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("capacity and fractions are validated")
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new TinyLfuPolicy<String, Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new TinyLfuPolicy<String, Integer>(10, 0.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new TinyLfuPolicy<String, Integer>(10, 1.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new TinyLfuPolicy<String, Integer>(10, 0.5, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TinyLfuPolicy<String, Integer>(10, 0.5, -0.1));
    }

    @Test
    @DisplayName("differential: agrees with a naive three-list model (sharing real sketch/doorkeeper instances) over random load")
    void differentialAgainstNaiveModel() {
        int capacity = 100;
        PolicyTestSupport.runDifferential("W-TinyLFU", cap -> new TinyLfuPolicy<>(cap),
                () -> new PolicyTestSupport.NaiveTinyLfu(capacity), capacity, 200, 40_000, 23);
    }
}
