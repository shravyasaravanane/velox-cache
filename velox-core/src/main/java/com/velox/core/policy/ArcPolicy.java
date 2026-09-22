package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.util.Hashing;
import com.velox.core.util.Invariants;

/**
 * <b>ARC</b> (Adaptive Replacement Cache; Megiddo &amp; Modha, 2003): the only policy in
 * this project that <b>tunes itself</b> while it runs, rather than using a fixed rule.
 *
 * <h2>Two lists become four</h2>
 *
 * ARC keeps the same recency/frequency split {@link SlruPolicy} and {@link TwoQueuePolicy}
 * do, but with a twist: the split point moves.
 *
 * <ul>
 *   <li><b>T1</b> — recently used once (recency); LRU-ordered.</li>
 *   <li><b>T2</b> — used at least twice (frequency); LRU-ordered. Any hit, wherever the
 *       entry came from, moves it here.</li>
 *   <li><b>B1</b> — a ghost list of keys recently evicted from T1.</li>
 *   <li><b>B2</b> — a ghost list of keys recently evicted from T2.</li>
 * </ul>
 *
 * <p>{@code p}, a number between 0 and the cache's capacity, is the <b>target size of
 * T1</b>. It starts at 0 (favouring T2, the proven side) and moves on every ghost hit:
 *
 * <ul>
 *   <li>A hit in <b>B1</b> means an entry evicted from the recency side was wanted again
 *       — T1 was too small. {@code p} moves <b>up</b>, by more when B1 is comparatively
 *       small next to B2 (a strong signal), less when it is large (a weak one).</li>
 *   <li>A hit in <b>B2</b> means the same on the frequency side, so {@code p} moves
 *       <b>down</b>, by the mirror-image amount.</li>
 * </ul>
 *
 * A workload that is mostly scans (one-hit keys) drives B1 hits rarely and B2 hits never,
 * so {@code p} stays low and T2 keeps most of the room. A workload with a real working set
 * that recirculates drives B1 hits often, and {@code p} rises to protect it. This is the
 * "adaptive" in the name: no configuration knob, no fixed fraction (contrast
 * {@link SlruPolicy}'s fixed 80%) — the cache learns the right split from its own ghost
 * hits, continuously, forever.
 *
 * <h2>Choosing a victim</h2>
 *
 * When room is needed, ARC evicts from T1 if T1 is <i>over</i> its target {@code p} — or,
 * on a tie ({@code T1.size() == p}), if the entry that triggered this eviction arrived via
 * a B2 ghost hit (a small tie-break favouring the side that just proved itself). Otherwise
 * it evicts from T2. The evicted key always becomes a ghost on the corresponding side.
 *
 * <h2>A deliberate simplification, stated plainly</h2>
 *
 * The original paper's admission algorithm has one further special case: when the
 * "directory" of real-plus-ghost recency entries (T1 + B1) is already exactly at capacity
 * <i>and entirely real</i> (B1 empty), it drops the T1 victim without creating a ghost, to
 * avoid a specific bookkeeping edge case around the {@code T1 + B1 <= c} bound. This
 * implementation does not special-case that: every eviction here becomes a ghost, and the
 * ghost lists are independently capped at capacity ({@link #trimGhosts()}), a simpler and
 * still-bounded rule. It preserves ARC's central idea — the adaptive {@code p}, driven by
 * ghost hits — which is what this policy exists to demonstrate; it does not reproduce the
 * source paper's bookkeeping to the letter. Differential-tested against a naive model that
 * makes the same simplification, so the two are checked for agreement with each other, not
 * against an external ARC implementation.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class ArcPolicy<K, V> implements EvictionPolicy<K, V> {

    static final int T1 = 0;
    static final int T2 = 1;

    private enum Arrival { FRESH, FROM_B1, FROM_B2 }

    private final IntrusiveLinkedList<K, V> t1 = new IntrusiveLinkedList<>();
    private final IntrusiveLinkedList<K, V> t2 = new IntrusiveLinkedList<>();
    private final GhostList<K, V> b1;
    private final GhostList<K, V> b2;
    private final int capacity;

    /** The adaptive target size of T1, in [0, capacity]. Starts at 0: cold, favouring T2. */
    private double p;

    /** How the key now being inserted arrived, set by {@link #beforeInsert} and consumed by {@link #onInsert}. */
    private Arrival pendingArrival = Arrival.FRESH;

    /** An ARC policy for a cache of {@code capacity} entries. */
    public ArcPolicy(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
        this.b1 = new GhostList<>(capacity);
        this.b2 = new GhostList<>(capacity);
    }

    @Override
    public void beforeInsert(K key) {
        int hash = Hashing.spread(key);

        Node<K, V> ghost1 = b1.find(key, hash);
        if (ghost1 != null) {
            p = Math.min(capacity, p + Math.max(1.0, (double) b2.size() / Math.max(1, b1.size())));
            b1.remove(ghost1);
            pendingArrival = Arrival.FROM_B1;
            return;
        }

        Node<K, V> ghost2 = b2.find(key, hash);
        if (ghost2 != null) {
            p = Math.max(0, p - Math.max(1.0, (double) b1.size() / Math.max(1, b2.size())));
            b2.remove(ghost2);
            pendingArrival = Arrival.FROM_B2;
            return;
        }

        pendingArrival = Arrival.FRESH;
    }

    @Override
    public void onInsert(Node<K, V> node) {
        if (pendingArrival == Arrival.FRESH) {
            // A key seen nowhere before starts on the recency side, same as every other
            // policy in this project: everything is judged on merit before it is trusted.
            node.setSegment(T1);
            t1.addToHead(node);
        } else {
            // A key that survived being forgotten has proved itself twice over (once
            // before eviction, once now): straight to the frequency side.
            node.setSegment(T2);
            t2.addToHead(node);
        }
        pendingArrival = Arrival.FRESH;
    }

    @Override
    public void onAccess(Node<K, V> node) {
        if (node.segment() == T1) {
            t1.unlink(node);
            node.setSegment(T2);
            t2.addToHead(node);
        } else {
            t2.moveToHead(node);
        }
    }

    @Override
    public void onEvict(Node<K, V> victim) {
        if (victim.segment() == T1) {
            b1.addNewest(victim.key(), victim.hash());
        } else {
            b2.addNewest(victim.key(), victim.hash());
        }
        trimGhosts();
    }

    @Override
    public void onRemove(Node<K, V> node) {
        segmentOf(node).unlink(node);
    }

    @Override
    public Node<K, V> selectVictim() {
        boolean evictFromT1 = t1.size() >= 1
                && (pendingArrival == Arrival.FROM_B2 ? t1.size() >= p : t1.size() > p);
        Node<K, V> primary = evictFromT1 ? t1.tail() : t2.tail();
        if (primary != null) {
            return primary;
        }
        return evictFromT1 ? t2.tail() : t1.tail();
    }

    @Override
    public void clear() {
        t1.clear();
        t2.clear();
        b1.clear();
        b2.clear();
        p = 0;
        pendingArrival = Arrival.FRESH;
    }

    @Override
    public String name() {
        return "ARC";
    }

    private IntrusiveLinkedList<K, V> segmentOf(Node<K, V> node) {
        return node.segment() == T1 ? t1 : t2;
    }

    /** Keeps each ghost list from growing past the cache's own capacity. See the class Javadoc. */
    private void trimGhosts() {
        while (b1.size() > capacity) {
            b1.removeOldest();
        }
        while (b2.size() > capacity) {
            b2.removeOldest();
        }
    }

    /** @return the current adaptive target size of T1, for tests and dashboards */
    public double targetT1Size() {
        return p;
    }

    /** @return keys in T1 (recency), most recently used first */
    public java.util.List<K> t1Keys() {
        return t1.keysFromMruToLru();
    }

    /** @return keys in T2 (frequency), most recently used first */
    public java.util.List<K> t2Keys() {
        return t2.keysFromMruToLru();
    }

    /** @return whether {@code key} is currently a ghost on the recency side (B1) */
    public boolean isRecencyGhost(K key) {
        return b1.find(key, Hashing.spread(key)) != null;
    }

    /** @return whether {@code key} is currently a ghost on the frequency side (B2) */
    public boolean isFrequencyGhost(K key) {
        return b2.find(key, Hashing.spread(key)) != null;
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        t1.assertInvariants();
        t2.assertInvariants();
        b1.assertInvariants();
        b2.assertInvariants();
        Invariants.check(t1.size() + t2.size() == expectedEntryCount,
                "ARC tracks " + (t1.size() + t2.size()) + " entries but the cache holds " + expectedEntryCount);
        Invariants.check(p >= 0 && p <= capacity, "p is " + p + ", outside [0, " + capacity + "]");
        Invariants.check(b1.size() <= capacity, "B1 holds " + b1.size() + " ghosts, above capacity " + capacity);
        Invariants.check(b2.size() <= capacity, "B2 holds " + b2.size() + " ghosts, above capacity " + capacity);
        t1.forEach(node -> Invariants.check(node.segment() == T1,
                node.key() + " is on the T1 list but is tagged T2"));
        t2.forEach(node -> Invariants.check(node.segment() == T2,
                node.key() + " is on the T2 list but is tagged T1"));
    }
}
