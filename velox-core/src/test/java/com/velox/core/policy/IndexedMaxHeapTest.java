package com.velox.core.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexedMaxHeapTest {

    @Test
    @DisplayName("an empty heap has no maximum")
    void emptyHeapHasNoMax() {
        var heap = new IndexedMaxHeap<String>();

        assertNull(heap.peekMax());
        assertEquals(0, heap.size());
        assertNull(heap.removeMax());
    }

    @Test
    @DisplayName("removeMax always returns the largest remaining priority")
    void removeMaxReturnsLargestFirst() {
        var heap = new IndexedMaxHeap<String>();
        heap.insert("low", 1);
        heap.insert("high", 100);
        heap.insert("mid", 50);

        assertEquals("high", heap.removeMax());
        assertEquals("mid", heap.removeMax());
        assertEquals("low", heap.removeMax());
        assertEquals(0, heap.size());
    }

    @Test
    @DisplayName("update repositions a key after its priority changes")
    void updateRepositions() {
        var heap = new IndexedMaxHeap<String>();
        heap.insert("A", 10);
        heap.insert("B", 20);
        heap.insert("C", 30);
        assertEquals("C", heap.peekMax());

        heap.update("C", 5);               // C is now the smallest

        assertEquals("B", heap.peekMax(), "B is now the largest after C dropped");

        heap.update("A", 1000);            // A is now by far the largest

        assertEquals("A", heap.peekMax());
    }

    @Test
    @DisplayName("update on an absent key is rejected")
    void updateRejectsAbsentKey() {
        var heap = new IndexedMaxHeap<String>();
        heap.insert("A", 1);

        assertThrows(IllegalArgumentException.class, () -> heap.update("never-inserted", 5));
    }

    @Test
    @DisplayName("contains reflects membership exactly")
    void containsTracksMembership() {
        var heap = new IndexedMaxHeap<String>();
        assertFalse(heap.contains("A"));

        heap.insert("A", 1);
        assertTrue(heap.contains("A"));

        heap.removeMax();
        assertFalse(heap.contains("A"));
    }

    @Test
    @DisplayName("against a naive sorted list over random inserts, updates and removals")
    void agreesWithANaiveModelUnderRandomLoad() {
        var heap = new IndexedMaxHeap<Integer>();
        var reference = new java.util.HashMap<Integer, Long>();     // key -> priority, naive "obviously correct" model
        var random = new Random(5);
        int nextKey = 0;
        List<Integer> present = new ArrayList<>();

        for (int step = 0; step < 20_000; step++) {
            int action = random.nextInt(3);
            if (action == 0 || present.isEmpty()) {
                int key = nextKey++;
                long priority = random.nextInt(1_000_000);
                heap.insert(key, priority);
                reference.put(key, priority);
                present.add(key);
            } else if (action == 1 && !present.isEmpty()) {
                int key = present.get(random.nextInt(present.size()));
                long newPriority = random.nextInt(1_000_000);
                heap.update(key, newPriority);
                reference.put(key, newPriority);
            } else if (!present.isEmpty()) {
                // The naive check: scan every remembered key for the true maximum.
                long expectedMax = Long.MIN_VALUE;
                for (int key : present) {
                    expectedMax = Math.max(expectedMax, reference.get(key));
                }
                Integer removed = heap.removeMax();
                assertEquals(expectedMax, reference.remove(removed),
                        "step " + step + ": removeMax did not return a key with the true maximum priority");
                present.remove((Integer) removed);
            }
            assertEquals(present.size(), heap.size(), "step " + step + ": size diverged");
        }
    }
}
