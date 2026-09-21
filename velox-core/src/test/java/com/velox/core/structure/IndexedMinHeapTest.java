package com.velox.core.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexedMinHeapTest {

    private static Node<String, Integer> node(String key, long deadline) {
        var n = new Node<String, Integer>(key, 0, key.hashCode());
        n.setExpiresAtNanos(deadline);
        return n;
    }

    // ------------------------------------------------------------------
    //  Basics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty heap has no minimum")
    void emptyHeap() {
        var heap = new IndexedMinHeap<String, Integer>();

        assertTrue(heap.isEmpty());
        assertNull(heap.peek());
        assertNull(heap.poll());
        heap.assertInvariants();
    }

    @Test
    @DisplayName("nodes come out in deadline order regardless of insertion order")
    void pollsInAscendingOrder() {
        var heap = new IndexedMinHeap<String, Integer>();
        var random = new Random(1);
        for (int i = 0; i < 500; i++) {
            heap.insert(node("k" + i, random.nextInt(10_000)));
            heap.assertInvariants();
        }

        long previous = Long.MIN_VALUE;
        while (!heap.isEmpty()) {
            long deadline = heap.peek().expiresAtNanos();
            assertSame(heap.peek(), heap.poll(), "peek must show exactly what poll returns");
            assertTrue(deadline >= previous, "deadlines went backwards: " + previous + " then " + deadline);
            previous = deadline;
            heap.assertInvariants();
        }
    }

    @Test
    @DisplayName("nodes with identical deadlines are all returned")
    void duplicateDeadlines() {
        var heap = new IndexedMinHeap<String, Integer>();
        for (int i = 0; i < 20; i++) {
            heap.insert(node("k" + i, 42));
        }

        int count = 0;
        while (heap.poll() != null) {
            count++;
        }

        assertEquals(20, count);
    }

    @Test
    @DisplayName("the backing array grows past its initial size")
    void growsPastInitialCapacity() {
        var heap = new IndexedMinHeap<String, Integer>();
        for (int i = 0; i < 1000; i++) {
            heap.insert(node("k" + i, 1000 - i));
        }
        heap.assertInvariants();
        assertEquals(1000, heap.size());
        assertEquals(1, heap.peek().expiresAtNanos());
    }

    // ------------------------------------------------------------------
    //  The reason the heap is "indexed": removal and repositioning
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an arbitrary node can be removed, and the rest stay ordered")
    void removeFromTheMiddle() {
        var heap = new IndexedMinHeap<String, Integer>();
        var nodes = new ArrayList<Node<String, Integer>>();
        for (int i = 0; i < 50; i++) {
            var n = node("k" + i, (i * 37) % 101);
            nodes.add(n);
            heap.insert(n);
        }

        // Remove a spread of nodes -- roots, leaves and interior ones.
        for (int i = 0; i < 50; i += 3) {
            assertTrue(heap.remove(nodes.get(i)));
            assertFalse(heap.contains(nodes.get(i)));
            assertEquals(-1, nodes.get(i).heapIndex(), "a removed node must forget its index");
            heap.assertInvariants();
        }

        long previous = Long.MIN_VALUE;
        while (!heap.isEmpty()) {
            long d = heap.poll().expiresAtNanos();
            assertTrue(d >= previous);
            previous = d;
        }
    }

    @Test
    @DisplayName("removing a node that is not in the heap is harmless")
    void removeAbsentNode() {
        var heap = new IndexedMinHeap<String, Integer>();
        heap.insert(node("in", 5));
        var stranger = node("out", 1);

        assertFalse(heap.remove(stranger));

        assertEquals(1, heap.size());
        heap.assertInvariants();
    }

    @Test
    @DisplayName("update moves a node whose deadline got earlier up to the root")
    void updateDecreaseKey() {
        var heap = new IndexedMinHeap<String, Integer>();
        Node<String, Integer> late = null;
        for (int i = 0; i < 30; i++) {
            var n = node("k" + i, 100 + i);
            if (i == 29) {
                late = n;
            }
            heap.insert(n);
        }

        late.setExpiresAtNanos(1);       // now the earliest of all
        heap.update(late);

        assertSame(late, heap.peek());
        heap.assertInvariants();
    }

    @Test
    @DisplayName("update moves a node whose deadline got later down towards the leaves")
    void updateIncreaseKey() {
        var heap = new IndexedMinHeap<String, Integer>();
        var first = node("first", 1);
        heap.insert(first);
        for (int i = 0; i < 30; i++) {
            heap.insert(node("k" + i, 100 + i));
        }

        first.setExpiresAtNanos(10_000); // the earliest becomes the latest
        heap.update(first);

        assertFalse(heap.peek() == first, "it must no longer be at the root");
        heap.assertInvariants();

        Node<String, Integer> last = null;
        while (!heap.isEmpty()) {
            last = heap.poll();
        }
        assertSame(first, last, "it should now come out last");
    }

    @Test
    @DisplayName("clear forgets every node's index")
    void clearResetsIndexes() {
        var heap = new IndexedMinHeap<String, Integer>();
        var a = node("a", 1);
        var b = node("b", 2);
        heap.insert(a);
        heap.insert(b);

        heap.clear();

        assertTrue(heap.isEmpty());
        assertEquals(-1, a.heapIndex());
        assertEquals(-1, b.heapIndex());
        assertFalse(heap.contains(a));
        heap.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Wrap-around
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ordering stays correct when the nanosecond counter wraps around")
    void survivesClockWraparound() {
        // System.nanoTime() can overflow Long.MAX_VALUE. A deadline just past
        // the wrap looks NUMERICALLY tiny (near Long.MIN_VALUE) but is actually
        // LATER in time. Comparing with '<' would order these backwards and
        // expire entries centuries early; comparing the DIFFERENCE stays right.
        var heap = new IndexedMinHeap<String, Integer>();
        var beforeWrap = node("before", Long.MAX_VALUE - 5);
        var afterWrap = node("after", Long.MIN_VALUE + 5);    // 11 ns later, across the wrap

        heap.insert(afterWrap);
        heap.insert(beforeWrap);

        assertSame(beforeWrap, heap.poll(), "the deadline before the wrap is the earlier one");
        assertSame(afterWrap, heap.poll());
    }

    // ------------------------------------------------------------------
    //  Differential test
    // ------------------------------------------------------------------

    @Test
    @DisplayName("matches a brute-force priority queue over 60,000 random operations")
    void differentialTest() {
        // The reference is an ArrayList scanned for its minimum: O(n) and
        // obviously right. Any disagreement means the sift logic or the
        // index bookkeeping is wrong.
        var heap = new IndexedMinHeap<String, Integer>();
        List<Node<String, Integer>> reference = new ArrayList<>();
        var random = new Random(99);
        int counter = 0;

        for (int step = 0; step < 60_000; step++) {
            int action = random.nextInt(10);

            if (action < 4 || reference.isEmpty()) {
                var n = node("k" + counter++, random.nextInt(1000));
                heap.insert(n);
                reference.add(n);

            } else if (action < 6) {
                long expectedMin = minDeadline(reference);
                Node<String, Integer> polled = heap.poll();
                assertEquals(expectedMin, polled.expiresAtNanos(), "poll returned a non-minimum at step " + step);
                reference.remove(polled);

            } else if (action < 8) {
                var victim = reference.remove(random.nextInt(reference.size()));
                assertTrue(heap.remove(victim));

            } else {
                var target = reference.get(random.nextInt(reference.size()));
                target.setExpiresAtNanos(random.nextInt(1000));
                heap.update(target);
            }

            assertEquals(reference.size(), heap.size(), "size diverged at step " + step);
            if (!reference.isEmpty()) {
                assertEquals(minDeadline(reference), heap.peek().expiresAtNanos(),
                        "peek diverged at step " + step);
            }
            heap.assertInvariants();
        }
    }

    private static long minDeadline(List<Node<String, Integer>> nodes) {
        long min = Long.MAX_VALUE;
        for (var n : nodes) {
            min = Math.min(min, n.expiresAtNanos());
        }
        return min;
    }
}
