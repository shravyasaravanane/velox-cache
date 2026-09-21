package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.util.Invariants;

/**
 * CLOCK (a.k.a. Second-Chance) -- an approximation of LRU that makes reads
 * nearly free.
 *
 * <h2>The idea</h2>
 *
 * Exact LRU must reorder a list on <b>every hit</b>. CLOCK replaces that with
 * a single bit per entry:
 *
 * <ul>
 *   <li><b>hit</b>: set the entry's reference bit. Nothing else. No pointers move.</li>
 *   <li><b>eviction</b>: sweep a "hand" around the entries. If the bit is set,
 *       clear it and move on -- the entry gets a <i>second chance</i>. If the
 *       bit is clear, evict it.</li>
 * </ul>
 *
 * <p>An entry survives a sweep only if it was used since the hand last passed.
 * That is a coarse version of "recently used", obtained without ever
 * maintaining an exact order.
 *
 * <h2>Why bother? The concurrency payoff</h2>
 *
 * Recall the central problem of Tier 2: with LRU, a <i>read</i> is a
 * <i>write</i> to shared structure, so all threads serialise on a lock. With
 * CLOCK a hit is one boolean store into the entry it already holds -- there is
 * no list to corrupt, so no lock is required around it. That is why the
 * PostgreSQL buffer pool uses a clock sweep and not LRU.
 *
 * <p>The cost is a slightly worse hit rate than exact LRU, because a single
 * bit forgets <i>how long ago</i> and <i>how often</i>. Measuring exactly
 * how much worse is a good benchmark result.
 *
 * <h2>Implementation note: the clock face, unrolled</h2>
 *
 * A textbook CLOCK is a circular array with a moving hand. We use the
 * equivalent <b>queue formulation</b>: the entries sit in a list, and the
 * tail plays the part of the hand.
 *
 * <pre>
 *   head &lt;-&gt; [new] &lt;-&gt; ... &lt;-&gt; [old] &lt;-&gt; tail
 *                                 ^
 *                               "hand"
 *
 *   sweep past a referenced entry  ==  clear its bit, rotate it to the head
 * </pre>
 *
 * Rotating tail-to-head is exactly the hand advancing one position on the
 * circle: the entry it just passed becomes the <i>last</i> one it will meet
 * on its next lap. Same algorithm, no separate hand index to keep in sync
 * with removals.
 *
 * <h2>Complexity</h2>
 *
 * {@link #onAccess} is O(1). {@link #selectVictim} is <b>amortised</b> O(1):
 * one call may rotate many entries, but each rotation clears a bit that an
 * earlier hit set, so total sweep work is bounded by total hits.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class ClockPolicy<K, V> implements EvictionPolicy<K, V> {

    private final IntrusiveLinkedList<K, V> ring = new IntrusiveLinkedList<>();

    @Override
    public void onInsert(Node<K, V> node) {
        node.setReferenced(false);   // a newcomer has not yet earned a second chance
        ring.addToHead(node);
    }

    @Override
    public void onAccess(Node<K, V> node) {
        node.setReferenced(true);    // the whole point: one store, no list surgery
    }

    @Override
    public void onRemove(Node<K, V> node) {
        ring.unlink(node);
    }

    @Override
    public Node<K, V> selectVictim() {
        // Terminates: every iteration clears one bit, so after at most `size`
        // rotations every bit is clear and the tail is returned.
        Node<K, V> candidate = ring.tail();
        while (candidate != null && candidate.isReferenced()) {
            candidate.setReferenced(false);   // spend its second chance
            ring.moveToHead(candidate);       // hand moves on
            candidate = ring.tail();
        }
        return candidate;
    }

    @Override
    public void clear() {
        ring.clear();
    }

    @Override
    public String name() {
        return "CLOCK";
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        ring.assertInvariants();
        Invariants.check(ring.size() == expectedEntryCount,
                "CLOCK is tracking " + ring.size() + " entries but the cache holds " + expectedEntryCount);
    }
}
