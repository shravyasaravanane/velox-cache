package com.velox.core.expiry;

import com.velox.core.structure.IndexedMinHeap;
import com.velox.core.structure.Node;

/**
 * Expiry scheduling backed by an {@link IndexedMinHeap}.
 *
 * <p>A thin adapter: the heap already does the work. It keeps the entry with
 * the earliest deadline at the root, so "is anything due?" is one comparison
 * against {@link IndexedMinHeap#peek()}, and draining k due entries costs
 * O(k log n).
 *
 * <p>Its distinguishing property is being <b>exact</b>: an entry is reported
 * the instant its deadline passes, not at the next tick boundary.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class HeapExpiryEngine<K, V> implements ExpiryEngine<K, V> {

    private final IndexedMinHeap<K, V> heap = new IndexedMinHeap<>();

    @Override
    public void schedule(Node<K, V> node) {
        if (heap.contains(node)) {
            heap.update(node);          // deadline moved: reposition in place
        } else {
            heap.insert(node);
        }
    }

    @Override
    public void cancel(Node<K, V> node) {
        heap.remove(node);              // returns false, harmlessly, if absent
    }

    @Override
    public Node<K, V> pollExpired(long nowNanos) {
        Node<K, V> next = heap.peek();
        // Subtraction, not '<=': stays correct if the nanosecond counter wraps.
        if (next == null || nowNanos - next.expiresAtNanos() < 0) {
            return null;
        }
        return heap.poll();
    }

    @Override
    public boolean isScheduled(Node<K, V> node) {
        return heap.contains(node);
    }

    @Override
    public int size() {
        return heap.size();
    }

    @Override
    public void clear() {
        heap.clear();
    }

    @Override
    public String name() {
        return "INDEXED_HEAP";
    }

    @Override
    public void assertInvariants() {
        heap.assertInvariants();
    }
}
