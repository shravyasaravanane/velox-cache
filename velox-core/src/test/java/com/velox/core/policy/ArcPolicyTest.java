package com.velox.core.policy;

import com.velox.core.VeloxCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A cache only asks its policy for a victim once it is full and a NEW key arrives -- the
 * {@code (capacity + 1)}-th distinct key put into it. Every test sizes its {@code put}s
 * around that rule so the eviction it checks actually happens where expected.
 */
class ArcPolicyTest {

    @Test
    @DisplayName("a new entry starts in T1 (recency); a second use promotes it to T2 (frequency)")
    void newEntryStartsInT1ThenPromotes() {
        var policy = new ArcPolicy<String, Integer>(20);
        var cache = new VeloxCache<String, Integer>(20, policy);

        cache.put("A", 1);
        assertEquals(java.util.List.of("A"), policy.t1Keys());
        assertEquals(java.util.List.of(), policy.t2Keys());

        cache.getIfPresent("A");
        assertEquals(java.util.List.of(), policy.t1Keys());
        assertEquals(java.util.List.of("A"), policy.t2Keys());
    }

    @Test
    @DisplayName("a scan of one-hit keys cannot evict an entry proven onto T2")
    void scanCannotEvictAProvenEntry() {
        // p starts at 0, so a resident T1 key is always preferred for eviction over a T2
        // key, for as long as nothing has yet taught ARC otherwise -- exactly the guard a
        // pure scan of never-repeated keys needs, since it never produces a ghost hit.
        var cache = new VeloxCache<String, Integer>(4, new ArcPolicy<>(4));
        cache.put("hot", 1);
        cache.getIfPresent("hot");            // promoted to T2

        for (int i = 0; i < 100; i++) {
            cache.put("scan" + i, i);         // each key used exactly once
        }

        assertNotNull(cache.getIfPresent("hot"), "a twice-used entry must survive a scan of one-hit keys");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("p rises on a B1 ghost hit and falls on a B2 ghost hit -- the adaptive part")
    void pAdaptsTowardTheSideThatProvesItselfRight() {
        var policy = new ArcPolicy<String, Integer>(2);
        var cache = new VeloxCache<String, Integer>(2, policy);
        assertEquals(0.0, policy.targetT1Size(), "cold start: p favours T2 entirely");

        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);          // 3rd distinct key: evicts A (T1) -> ghost in B1

        cache.put("A", 99);         // A is asked for again while a B1 ghost: p rises

        assertEquals(1.0, policy.targetT1Size(), "a B1 ghost hit is evidence T1 should be bigger");
        assertEquals(java.util.List.of("A"), policy.t2Keys(), "A re-enters straight onto T2");

        cache.put("D", 4);          // 5th distinct key: with p=1, T2's only entry (A) is now evicted -> ghost in B2

        cache.put("A", 7);          // A is asked for again while a B2 ghost: p falls back

        assertEquals(0.0, policy.targetT1Size(), "a B2 ghost hit is evidence T2 was over-favoured");
        assertEquals(java.util.List.of("A"), policy.t2Keys());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("capacity must be at least 1")
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ArcPolicy<String, Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new ArcPolicy<String, Integer>(-1));
    }

    @Test
    @DisplayName("differential: agrees with a naive four-list model over random load")
    void differentialAgainstNaiveModel() {
        int capacity = 30;
        PolicyTestSupport.runDifferential("ARC", ArcPolicy::new,
                () -> new PolicyTestSupport.NaiveArc(capacity), capacity, 60, 40_000, 19);
    }
}
