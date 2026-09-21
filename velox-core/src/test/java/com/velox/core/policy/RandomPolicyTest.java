package com.velox.core.policy;

import com.velox.core.VeloxCache;
import com.velox.core.structure.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomPolicyTest {

    private static Node<String, Integer> node(String key) {
        return new Node<>(key, 0, key.hashCode());
    }

    @Test
    @DisplayName("swap-remove keeps every node's slot pointing at its real array index")
    void swapRemoveKeepsSlotsConsistent() {
        var policy = new RandomPolicy<String, Integer>();
        var nodes = new ArrayList<Node<String, Integer>>();
        for (int i = 0; i < 10; i++) {
            var n = node("k" + i);
            nodes.add(n);
            policy.onInsert(n);
            policy.assertInvariants(nodes.size());
        }

        // Remove from the front, middle and back: each exercises a different
        // path through "move the last element into the hole".
        for (int index : new int[]{0, 4, 6, 0, 2}) {
            policy.onRemove(nodes.remove(index));
            policy.assertInvariants(nodes.size());
        }

        assertEquals(5, nodes.size());
    }

    @Test
    @DisplayName("removing the only entry leaves an empty policy")
    void removeLastRemainingEntry() {
        var policy = new RandomPolicy<String, Integer>();
        var only = node("A");
        policy.onInsert(only);

        policy.onRemove(only);

        assertNull(policy.selectVictim());
        policy.assertInvariants(0);
        assertEquals(-1, only.slot(), "a removed node must be marked untracked");
    }

    @Test
    @DisplayName("the backing array grows past its initial size")
    void growsBeyondInitialCapacity() {
        var policy = new RandomPolicy<String, Integer>();
        for (int i = 0; i < 1000; i++) {
            policy.onInsert(node("k" + i));
        }
        policy.assertInvariants(1000);
    }

    @Test
    @DisplayName("victims are chosen roughly uniformly")
    void victimsAreUniform() {
        var policy = new RandomPolicy<String, Integer>(1234);
        for (int i = 0; i < 10; i++) {
            policy.onInsert(node("k" + i));
        }

        Map<String, Integer> picks = new HashMap<>();
        int trials = 100_000;
        for (int i = 0; i < trials; i++) {
            picks.merge(policy.selectVictim().key(), 1, Integer::sum);
        }

        // Expected 10,000 per key with a standard deviation of about 95, so a
        // band of +/-1,000 is more than ten sigma: a real bias would blow
        // through it, honest randomness never will.
        assertEquals(10, picks.size(), "every entry must be reachable");
        for (var e : picks.entrySet()) {
            assertTrue(e.getValue() > 9_000 && e.getValue() < 11_000,
                    e.getKey() + " was picked " + e.getValue() + " times out of " + trials);
        }
    }

    @Test
    @DisplayName("the same seed reproduces the same eviction sequence")
    void seededRunsAreReproducible() {
        assertEquals(evictionSequence(99), evictionSequence(99),
                "identical seeds must give identical runs, or benchmarks cannot be repeated");
        assertNotEquals(evictionSequence(99), evictionSequence(100),
                "different seeds should explore different sequences");
    }

    private static List<String> evictionSequence(long seed) {
        var cache = new VeloxCache<String, Integer>(5, new RandomPolicy<>(seed));
        var order = new ArrayList<String>();
        for (int i = 0; i < 50; i++) {
            cache.put("k" + i, i);
        }
        for (int i = 0; i < 50; i++) {
            if (cache.containsKey("k" + i)) {
                order.add("k" + i);
            }
        }
        return order;
    }

    @Test
    @DisplayName("holds up under random puts, gets and invalidations")
    void randomOperationsKeepInvariants() {
        var cache = new VeloxCache<String, Integer>(30, new RandomPolicy<>(7));
        var random = new Random(3);

        for (int step = 0; step < 50_000; step++) {
            String key = "k" + random.nextInt(100);
            switch (random.nextInt(3)) {
                case 0 -> cache.put(key, step);
                case 1 -> cache.getIfPresent(key);
                default -> cache.invalidate(key);
            }
            assertTrue(cache.size() <= 30);
            if (step % 500 == 0) {
                cache.assertInvariants();
            }
        }
        cache.assertInvariants();
    }
}
