package com.velox.core.structure;

import com.velox.core.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpenAddressingMap}.
 */
class OpenAddressingMapTest {

    private static Node<String, Integer> node(String key, int value) {
        return new Node<>(key, value, Hashing.spread(key));
    }

    /** Builds a node with a hash we choose, to force collisions on purpose. */
    private static Node<String, Integer> nodeWithHash(String key, int value, int hash) {
        return new Node<>(key, value, hash);
    }

    private static Node<String, Integer> find(OpenAddressingMap<String, Integer> map, String key) {
        return map.get(key, Hashing.spread(key));
    }

    private static Node<String, Integer> delete(OpenAddressingMap<String, Integer> map, String key) {
        return map.remove(key, Hashing.spread(key));
    }

    // ------------------------------------------------------------------
    //  Basics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a new map is empty and finds nothing")
    void newMapIsEmpty() {
        var map = new OpenAddressingMap<String, Integer>(16);

        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertNull(find(map, "missing"));
        map.assertInvariants();
    }

    @Test
    @DisplayName("an entry can be stored and found again")
    void putThenGet() {
        var map = new OpenAddressingMap<String, Integer>(16);
        var a = node("A", 1);

        assertNull(map.put(a), "storing a new key returns null");

        assertSame(a, find(map, "A"));
        assertEquals(1, map.size());
        assertTrue(map.containsKey("A", Hashing.spread("A")));
        map.assertInvariants();
    }

    @Test
    @DisplayName("storing the same key again replaces the entry without changing size")
    void putReplacesExistingKey() {
        var map = new OpenAddressingMap<String, Integer>(16);
        var first = node("A", 1);
        var second = node("A", 2);
        map.put(first);

        Node<String, Integer> displaced = map.put(second);

        assertSame(first, displaced, "the old node must be handed back");
        assertSame(second, find(map, "A"));
        assertEquals(1, map.size(), "replacing must not grow the map");
        map.assertInvariants();
    }

    @Test
    @DisplayName("removing an entry makes it unfindable")
    void removeDeletesEntry() {
        var map = new OpenAddressingMap<String, Integer>(16);
        var a = node("A", 1);
        map.put(a);

        assertSame(a, delete(map, "A"));

        assertNull(find(map, "A"));
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
        map.assertInvariants();
    }

    @Test
    @DisplayName("removing a key that was never there returns null")
    void removeAbsentKey() {
        var map = new OpenAddressingMap<String, Integer>(16);
        map.put(node("A", 1));

        assertNull(delete(map, "ghost"));

        assertEquals(1, map.size(), "a failed remove must not change the map");
        map.assertInvariants();
    }

    @Test
    @DisplayName("clear empties the map but keeps the table allocated")
    void clearEmptiesMap() {
        var map = new OpenAddressingMap<String, Integer>(16);
        map.put(node("A", 1));
        map.put(node("B", 2));
        int capacityBefore = map.capacity();

        map.clear();

        assertTrue(map.isEmpty());
        assertNull(find(map, "A"));
        assertEquals(capacityBefore, map.capacity());
        map.assertInvariants();
    }

    @Test
    @DisplayName("forEach visits every entry exactly once")
    void forEachVisitsAllEntries() {
        var map = new OpenAddressingMap<String, Integer>(16);
        for (int i = 0; i < 20; i++) {
            map.put(node("k" + i, i));
        }

        var visited = new ArrayList<String>();
        map.forEach(n -> visited.add(n.key()));

        assertEquals(20, visited.size());
        assertEquals(20, visited.stream().distinct().count(), "no entry may be visited twice");
    }

    // ------------------------------------------------------------------
    //  Collisions — exercising the probe logic directly
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keys that hash to the same slot are all stored and retrievable")
    void handlesTotalHashCollisions() {
        var map = new OpenAddressingMap<String, Integer>(16);

        // Every key claims the exact same ideal slot. This is the worst case
        // for open addressing and forces a long probe run through the table.
        var nodes = new ArrayList<Node<String, Integer>>();
        for (int i = 0; i < 10; i++) {
            var n = nodeWithHash("collide" + i, i, 0);
            nodes.add(n);
            map.put(n);
            map.assertInvariants();
        }

        assertEquals(10, map.size());
        for (int i = 0; i < 10; i++) {
            assertSame(nodes.get(i), map.get("collide" + i, 0),
                    "collide" + i + " got lost in the probe chain");
        }
    }

    @Test
    @DisplayName("removing from the middle of a collision run keeps the rest reachable")
    void backwardShiftKeepsProbeChainIntact() {
        var map = new OpenAddressingMap<String, Integer>(16);

        // All eight keys collide, so they occupy a contiguous run of slots.
        for (int i = 0; i < 8; i++) {
            map.put(nodeWithHash("c" + i, i, 0));
        }

        // Delete from the middle. With tombstones this is where a naive
        // implementation breaks: the hole ends the probe chain early and the
        // entries after it become invisible even though size() still counts them.
        assertNotNull(map.remove("c3", 0));
        map.assertInvariants();

        assertEquals(7, map.size());
        assertNull(map.get("c3", 0));
        for (int i = 0; i < 8; i++) {
            if (i == 3) {
                continue;
            }
            assertNotNull(map.get("c" + i, 0),
                    "c" + i + " became unreachable after removing c3");
        }
    }

    @Test
    @DisplayName("a run can be emptied from the front without losing anyone")
    void removingHeadOfRunRepeatedly() {
        var map = new OpenAddressingMap<String, Integer>(32);
        for (int i = 0; i < 12; i++) {
            map.put(nodeWithHash("c" + i, i, 0));
        }

        for (int i = 0; i < 12; i++) {
            assertNotNull(map.remove("c" + i, 0), "c" + i + " vanished before we removed it");
            map.assertInvariants();
            for (int j = i + 1; j < 12; j++) {
                assertNotNull(map.get("c" + j, 0),
                        "c" + j + " became unreachable after removing c" + i);
            }
        }

        assertTrue(map.isEmpty());
    }

    // ------------------------------------------------------------------
    //  Growth
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the table grows automatically and keeps every entry")
    void growsWithoutLosingEntries() {
        var map = new OpenAddressingMap<String, Integer>(4);
        int startingCapacity = map.capacity();

        for (int i = 0; i < 500; i++) {
            map.put(node("k" + i, i));
            map.assertInvariants();
        }

        assertTrue(map.capacity() > startingCapacity, "the table should have grown");
        assertEquals(500, map.size());
        for (int i = 0; i < 500; i++) {
            Node<String, Integer> found = find(map, "k" + i);
            assertNotNull(found, "k" + i + " was lost during a resize");
            assertEquals(i, found.value());
        }
    }

    @Test
    @DisplayName("the table never exceeds its load factor")
    void respectsLoadFactor() {
        var map = new OpenAddressingMap<String, Integer>(16);
        for (int i = 0; i < 1000; i++) {
            map.put(node("k" + i, i));
            assertTrue(map.size() <= map.capacity() * 0.75,
                    "load factor exceeded at " + map.size() + "/" + map.capacity());
        }
    }

    // ------------------------------------------------------------------
    //  Churn — proving we have no tombstone decay
    // ------------------------------------------------------------------

    @Test
    @DisplayName("heavy insert/remove churn does not degrade the table")
    void survivesHeavyChurn() {
        // A cache evicts constantly, so this pattern is its entire life.
        // An implementation using tombstones would slowly fill with dead
        // markers here and decay towards an O(n) scan. Backward-shift
        // deletion leaves no debris at all.
        var map = new OpenAddressingMap<String, Integer>(64);
        int capacityAfterWarmup = 0;

        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 200; i++) {
                map.put(node("round" + round + "key" + i, i));
            }
            for (int i = 0; i < 200; i++) {
                assertNotNull(delete(map, "round" + round + "key" + i));
            }
            assertTrue(map.isEmpty(), "round " + round + " left entries behind");
            map.assertInvariants();

            if (round == 0) {
                capacityAfterWarmup = map.capacity();
            }
        }

        assertEquals(capacityAfterWarmup, map.capacity(),
                "the table grew over repeated churn — that is tombstone-style decay");
    }

    // ------------------------------------------------------------------
    //  The differential test — the one that finds real bugs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("matches java.util.HashMap exactly over 200,000 random operations")
    void behavesIdenticallyToJavaHashMap() {
        // We cannot enumerate every situation by hand, so instead we run our
        // map and a known-correct one side by side on the same random
        // operations and demand identical answers every time.
        //
        // This is "differential testing", and it is far more effective than
        // hand-written cases: it explores states nobody thought to write down.
        // The fixed seed means any failure reproduces exactly.
        var ours = new OpenAddressingMap<String, Integer>(16);
        Map<String, Integer> reference = new HashMap<>();
        var random = new Random(20260921);

        int keySpace = 500;   // small, so collisions and reuse happen constantly

        for (int step = 0; step < 200_000; step++) {
            String key = "key" + random.nextInt(keySpace);
            int hash = Hashing.spread(key);
            int action = random.nextInt(10);

            if (action < 5) {
                // PUT (50%)
                int value = random.nextInt(1000);
                Node<String, Integer> displaced = ours.put(new Node<>(key, value, hash));
                Integer referenceDisplaced = reference.put(key, value);

                if (referenceDisplaced == null) {
                    assertNull(displaced, "step " + step + ": expected a new key for " + key);
                } else {
                    assertNotNull(displaced, "step " + step + ": expected a replacement for " + key);
                    assertEquals(referenceDisplaced, displaced.value(),
                            "step " + step + ": wrong displaced value for " + key);
                }

            } else if (action < 8) {
                // GET (30%)
                Node<String, Integer> found = ours.get(key, hash);
                Integer expected = reference.get(key);

                if (expected == null) {
                    assertNull(found, "step " + step + ": " + key + " should be absent");
                } else {
                    assertNotNull(found, "step " + step + ": " + key + " should be present");
                    assertEquals(expected, found.value(), "step " + step + ": wrong value for " + key);
                }

            } else {
                // REMOVE (20%)
                Node<String, Integer> removed = ours.remove(key, hash);
                Integer expected = reference.remove(key);

                if (expected == null) {
                    assertNull(removed, "step " + step + ": " + key + " was not there to remove");
                } else {
                    assertNotNull(removed, "step " + step + ": " + key + " should have been removed");
                    assertEquals(expected, removed.value(),
                            "step " + step + ": wrong removed value for " + key);
                }
            }

            assertEquals(reference.size(), ours.size(), "size diverged at step " + step);

            // Full structural check periodically — it is O(n), so running it
            // every step would make this test take minutes.
            if (step % 1000 == 0) {
                ours.assertInvariants();
            }
        }

        // Final check: every key the reference holds must be findable in ours,
        // with the same value, and our map must hold nothing extra.
        ours.assertInvariants();
        assertEquals(reference.size(), ours.size());

        for (Map.Entry<String, Integer> entry : reference.entrySet()) {
            Node<String, Integer> found = ours.get(entry.getKey(), Hashing.spread(entry.getKey()));
            assertNotNull(found, entry.getKey() + " is missing from our map");
            assertEquals(entry.getValue(), found.value());
        }

        var extras = new ArrayList<String>();
        ours.forEach(n -> {
            if (!reference.containsKey(n.key())) {
                extras.add(n.key());
            }
        });
        assertTrue(extras.isEmpty(), "our map holds keys the reference does not: " + extras);
    }

    // ------------------------------------------------------------------
    //  Probe quality — showing Robin Hood actually does its job
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Robin Hood keeps the worst-case probe distance close to the average")
    void robinHoodBoundsProbeDistance() {
        // Fill the map to just under its load factor, then measure how far the
        // unluckiest key sits from home.
        //
        // This is the test that proves Robin Hood is doing something. Plain
        // linear probing at 75% load produces a long tail: most entries sit at
        // distance 0-2, but the worst ends up dozens of slots out, and every
        // lookup for it pays that cost. Robin Hood cannot reduce the TOTAL
        // displacement, but by making richer entries surrender their slots it
        // spreads the displacement evenly, collapsing the maximum toward the
        // average.
        // Note the constructor takes EXPECTED ENTRIES, not slots: it sizes the
        // table so that many entries fit without ever resizing. So we ask for
        // 8192 entries (giving 16384 slots) and then fill to just under the
        // 75% resize threshold, which is the regime we actually want to test.
        var map = new OpenAddressingMap<String, Integer>(8192);
        int entries = (int) (map.capacity() * 0.75) - 1;
        for (int i = 0; i < entries; i++) {
            map.put(node("key" + i, i));
        }
        map.assertInvariants();
        assertEquals(entries, map.size(), "the table resized when it should not have");

        int worst = map.maxProbeDistance();
        double average = map.averageProbeDistance();
        double load = (double) map.size() / map.capacity();

        System.out.printf(
                "  [probe quality] %d entries, load %.2f -> average distance %.2f, worst %d%n",
                map.size(), load, average, worst);

        assertTrue(load > 0.70, "the table should be near its load factor, was " + load);

        // At 75% load the expected average is a small constant, independent of
        // how many entries there are. That constant-ness is the O(1) claim.
        assertTrue(average < 3.0,
                "average probe distance " + average + " is too high — lookups are not O(1)");

        // The real point: the worst case stays within a small multiple of the
        // average. Unbounded linear probing would blow well past this.
        assertTrue(worst < 40,
                "worst probe distance " + worst + " suggests the Robin Hood swap is not working");

        // And the structural proof lives in assertInvariants(), which checks
        // psl(i) <= psl(i-1) + 1 across the entire table. That inequality only
        // holds if each cluster is sorted by ideal slot, which is exactly what
        // the swap logic maintains.
    }
}
