package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.util.Invariants;

/**
 * First In, First Out -- evict whatever has been in the cache longest,
 * regardless of how often or how recently it was used.
 *
 * <h2>Why include a policy this simple?</h2>
 *
 * FIFO is the <b>floor</b>. It uses no information about the workload at all,
 * so every smarter policy has to justify its complexity by beating it. If an
 * algorithm cannot clearly out-score FIFO on some workload, that is a finding
 * worth reporting rather than an embarrassment.
 *
 * <h2>How it differs from LRU</h2>
 *
 * Structurally almost identical -- same list, same O(1) operations. The one
 * difference is a single line: {@link #onAccess} does <b>nothing</b>.
 *
 * <pre>
 *   LRU:   a hit moves the entry to the front   (recency is tracked)
 *   FIFO:  a hit changes nothing                (only insertion order counts)
 * </pre>
 *
 * So a key that is requested constantly still gets evicted the moment it
 * reaches the front of the line. That is FIFO's weakness, and also the source
 * of its one virtue: <b>reads are genuinely read-only</b>. No list mutation
 * on a hit means no lock is needed for the policy on the read path -- the
 * very problem Tier 2 spends effort on for LRU.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class FifoPolicy<K, V> implements EvictionPolicy<K, V> {

    /** Insertion order: newest at the head, oldest (the next victim) at the tail. */
    private final IntrusiveLinkedList<K, V> queue = new IntrusiveLinkedList<>();

    @Override
    public void onInsert(Node<K, V> node) {
        queue.addToHead(node);
    }

    @Override
    public void onAccess(Node<K, V> node) {
        // Deliberately empty: this is the entire difference between FIFO and LRU.
    }

    @Override
    public void onRemove(Node<K, V> node) {
        queue.unlink(node);
    }

    @Override
    public Node<K, V> selectVictim() {
        return queue.tail();
    }

    @Override
    public void clear() {
        queue.clear();
    }

    @Override
    public String name() {
        return "FIFO";
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        queue.assertInvariants();
        Invariants.check(queue.size() == expectedEntryCount,
                "FIFO is tracking " + queue.size() + " entries but the cache holds " + expectedEntryCount);
    }
}
