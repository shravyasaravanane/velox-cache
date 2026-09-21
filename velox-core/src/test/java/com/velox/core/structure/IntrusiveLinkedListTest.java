package com.velox.core.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IntrusiveLinkedList}.
 *
 * <h2>How to read these tests</h2>
 *
 * Each test does three things: set up a known state, perform one operation,
 * then check both the visible result AND {@code assertInvariants()} — which
 * verifies the list's internal pointers are still coherent.
 *
 * <p>That second check is the important one. A pointer bug usually does not
 * change what {@code size()} reports or what the first few nodes look like;
 * it corrupts the structure quietly. Calling {@code assertInvariants()} after
 * every operation means a broken pointer is caught by the very next
 * assertion, in the test that caused it.
 */
class IntrusiveLinkedListTest {

    /** Convenience: build a node. The hash does not matter to the list. */
    private static Node<String, Integer> node(String key, int value) {
        return new Node<>(key, value, key.hashCode());
    }

    // ------------------------------------------------------------------
    //  Basic state
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a new list is empty and has no head or tail entry")
    void newListIsEmpty() {
        var list = new IntrusiveLinkedList<String, Integer>();

        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
        assertNull(list.head(), "an empty list has no MRU entry");
        assertNull(list.tail(), "an empty list has no LRU entry");
        list.assertInvariants();
    }

    @Test
    @DisplayName("addToHead puts the newest entry at the front")
    void addToHeadOrdersNewestFirst() {
        var list = new IntrusiveLinkedList<String, Integer>();

        list.addToHead(node("A", 1));
        list.addToHead(node("B", 2));
        list.addToHead(node("C", 3));

        // Newest first: C was added last, so it is the most recently used.
        assertEquals(List.of("C", "B", "A"), list.keysFromMruToLru());
        assertEquals(3, list.size());
        assertEquals("C", list.head().key());
        assertEquals("A", list.tail().key(), "A was added first, so it is the LRU entry");
        list.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  moveToHead — what happens on a cache HIT
    // ------------------------------------------------------------------

    @Test
    @DisplayName("moveToHead promotes an entry from the middle")
    void moveToHeadFromMiddle() {
        var list = new IntrusiveLinkedList<String, Integer>();
        var a = node("A", 1);
        var b = node("B", 2);
        var c = node("C", 3);
        list.addToHead(a);
        list.addToHead(b);
        list.addToHead(c);
        // state: C, B, A

        list.moveToHead(b);   // "B was just accessed"

        assertEquals(List.of("B", "C", "A"), list.keysFromMruToLru());
        assertEquals(3, list.size(), "moving must not change the number of entries");
        list.assertInvariants();
    }

    @Test
    @DisplayName("moveToHead on the LRU entry rescues it from eviction")
    void moveToHeadFromTail() {
        var list = new IntrusiveLinkedList<String, Integer>();
        var a = node("A", 1);
        list.addToHead(a);
        list.addToHead(node("B", 2));
        list.addToHead(node("C", 3));
        // state: C, B, A  — A is next to be evicted

        list.moveToHead(a);

        assertEquals(List.of("A", "C", "B"), list.keysFromMruToLru());
        assertEquals("B", list.tail().key(), "B is now the eviction candidate");
        list.assertInvariants();
    }

    @Test
    @DisplayName("moveToHead on the entry that is already MRU changes nothing")
    void moveToHeadWhenAlreadyHead() {
        var list = new IntrusiveLinkedList<String, Integer>();
        list.addToHead(node("A", 1));
        var c = node("C", 3);
        list.addToHead(c);
        // state: C, A

        list.moveToHead(c);   // hits the early-return fast path

        assertEquals(List.of("C", "A"), list.keysFromMruToLru());
        assertEquals(2, list.size());
        list.assertInvariants();
    }

    @Test
    @DisplayName("moveToHead works on a single-entry list")
    void moveToHeadSingleEntry() {
        var list = new IntrusiveLinkedList<String, Integer>();
        var only = node("A", 1);
        list.addToHead(only);

        list.moveToHead(only);

        assertEquals(List.of("A"), list.keysFromMruToLru());
        assertEquals(1, list.size());
        list.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  unlink and removeTail — what happens on EVICTION
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unlink removes an entry from the middle without touching the rest")
    void unlinkFromMiddle() {
        var list = new IntrusiveLinkedList<String, Integer>();
        list.addToHead(node("A", 1));
        var b = node("B", 2);
        list.addToHead(b);
        list.addToHead(node("C", 3));
        // state: C, B, A

        list.unlink(b);

        assertEquals(List.of("C", "A"), list.keysFromMruToLru());
        assertEquals(2, list.size());
        list.assertInvariants();
    }

    @Test
    @DisplayName("removeTail evicts the least recently used entry")
    void removeTailEvictsLru() {
        var list = new IntrusiveLinkedList<String, Integer>();
        var a = node("A", 1);
        list.addToHead(a);
        list.addToHead(node("B", 2));
        list.addToHead(node("C", 3));
        // state: C, B, A

        Node<String, Integer> evicted = list.removeTail();

        assertSame(a, evicted, "A was the LRU entry, so A must be the victim");
        assertEquals(List.of("C", "B"), list.keysFromMruToLru());
        assertEquals(2, list.size());
        list.assertInvariants();
    }

    @Test
    @DisplayName("removeTail on an empty list returns null rather than throwing")
    void removeTailOnEmptyList() {
        var list = new IntrusiveLinkedList<String, Integer>();

        assertNull(list.removeTail());

        assertEquals(0, list.size());
        list.assertInvariants();
    }

    @Test
    @DisplayName("removing every entry leaves a clean empty list")
    void removeAllEntries() {
        var list = new IntrusiveLinkedList<String, Integer>();
        list.addToHead(node("A", 1));
        list.addToHead(node("B", 2));

        list.removeTail();
        list.assertInvariants();
        list.removeTail();

        assertTrue(list.isEmpty());
        assertNull(list.head());
        assertNull(list.tail());
        list.assertInvariants();
    }

    @Test
    @DisplayName("clear empties the list")
    void clearEmptiesList() {
        var list = new IntrusiveLinkedList<String, Integer>();
        list.addToHead(node("A", 1));
        list.addToHead(node("B", 2));
        list.addToHead(node("C", 3));

        list.clear();

        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
        assertEquals(List.of(), list.keysFromMruToLru());
        list.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  The worked example from docs/LEARN-01-FOUNDATIONS.md
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reproduces the hand-traced LRU example from the learning guide")
    void reproducesWorkedExample() {
        // Capacity 3. Operations:
        //   put(A) put(B) put(C) get(A) put(D)
        // Expected: put(D) evicts B, because get(A) rescued A.
        var list = new IntrusiveLinkedList<String, Integer>();

        var a = node("A", 1);
        var b = node("B", 2);
        var c = node("C", 3);
        var d = node("D", 4);

        list.addToHead(a);                                        // [A]
        assertEquals(List.of("A"), list.keysFromMruToLru());

        list.addToHead(b);                                        // [B, A]
        assertEquals(List.of("B", "A"), list.keysFromMruToLru());

        list.addToHead(c);                                        // [C, B, A]  — full
        assertEquals(List.of("C", "B", "A"), list.keysFromMruToLru());

        list.moveToHead(a);                                       // get(A) -> [A, C, B]
        assertEquals(List.of("A", "C", "B"), list.keysFromMruToLru());

        Node<String, Integer> evicted = list.removeTail();        // make room
        assertSame(b, evicted, "B must be evicted — A was rescued by the get()");

        list.addToHead(d);                                        // [D, A, C]
        assertEquals(List.of("D", "A", "C"), list.keysFromMruToLru());

        list.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Stress test — the one that actually finds bugs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("survives 20,000 random operations with invariants intact")
    void randomOperationStressTest() {
        var list = new IntrusiveLinkedList<String, Integer>();
        var live = new ArrayList<Node<String, Integer>>();   // our own record of what is in the list
        var random = new Random(42);                          // fixed seed = reproducible failures

        for (int i = 0; i < 20_000; i++) {
            int action = random.nextInt(4);

            if (action == 0 || live.isEmpty()) {
                // ADD
                var fresh = node("k" + i, i);
                list.addToHead(fresh);
                live.add(0, fresh);

            } else if (action == 1) {
                // MOVE a random entry to the head
                int index = random.nextInt(live.size());
                var picked = live.remove(index);
                list.moveToHead(picked);
                live.add(0, picked);

            } else if (action == 2) {
                // EVICT the LRU entry
                Node<String, Integer> evicted = list.removeTail();
                var expected = live.remove(live.size() - 1);
                assertSame(expected, evicted, "wrong victim chosen at step " + i);

            } else {
                // UNLINK a random entry
                int index = random.nextInt(live.size());
                var picked = live.remove(index);
                list.unlink(picked);
            }

            // The list must agree with our independent record, every single step.
            assertEquals(live.size(), list.size(), "size diverged at step " + i);
            list.assertInvariants();
        }

        // Final deep check: the full order must match, not just the count.
        List<String> expectedKeys = live.stream().map(Node::key).toList();
        assertEquals(expectedKeys, list.keysFromMruToLru(), "final ordering diverged");
    }
}
