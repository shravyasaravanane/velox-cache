package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.util.Hashing;
import com.velox.core.util.Invariants;

import java.util.Objects;

/**
 * <b>2Q</b> (Johnson &amp; Shasha, 1994): three queues, and a stricter bar for "proven
 * popular" than {@link SlruPolicy}.
 *
 * <h2>The three queues</h2>
 *
 * <ul>
 *   <li><b>A1in</b> — a plain FIFO queue (no reordering on a hit) holding entries seen
 *       exactly once, recently. Sized to about 25% of the cache.</li>
 *   <li><b>A1out</b> — a <b>ghost list</b>: the bare keys (no values) of entries recently
 *       pushed out of A1in. Sized to about 50% of the cache.</li>
 *   <li><b>Am</b> — an ordinary LRU list for entries that have earned their place.</li>
 * </ul>
 *
 * <h2>Why a hit in A1in does nothing</h2>
 *
 * This is the detail that tells 2Q apart from {@link SlruPolicy}. SLRU promotes an
 * entry the moment it is touched a second time, however soon that second touch comes.
 * 2Q deliberately does <b>not</b>: a second touch while still in A1in is left exactly
 * where it is. A key is promoted to Am only by a much stronger signal — it must survive
 * being evicted from A1in as a ghost, and then be asked for <i>again</i>. Two references
 * close together (typical of one burst of interest, e.g. a page rendering the same
 * partial twice) are not enough; interest that persists across an eviction is. That
 * makes 2Q the more skeptical of the two, at the cost of promoting slower.
 *
 * <h2>How the ghost list changes what gets evicted</h2>
 *
 * When {@link #selectVictim()} must take from A1in (because A1in is over its target
 * size), the victim's key moves to A1out instead of vanishing — {@link #onEvict} is the
 * hook for this, because it fires only for a capacity eviction, never for an explicit
 * {@code invalidate} or an expiry (see {@link EvictionPolicy#onEvict}). A later
 * {@link #beforeInsert} that finds the incoming key already in A1out removes it from
 * A1out and routes the new node straight into Am — the promotion this queue exists to
 * grant. Am's own evictions do not become ghosts: 2Q only gives second chances to A1in
 * survivors, not to entries that had already proven themselves and lost their place in Am.
 *
 * <h2>Costs</h2>
 *
 * Every operation is O(1). The ghost list adds one hash-map entry per remembered key
 * (no value, so it is cheap) and is trimmed to its target size on every eviction.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class TwoQueuePolicy<K, V> implements EvictionPolicy<K, V> {

    static final int A1_IN = 0;
    static final int MAIN = 1;

    private final IntrusiveLinkedList<K, V> a1in = new IntrusiveLinkedList<>();
    private final IntrusiveLinkedList<K, V> main = new IntrusiveLinkedList<>();
    private final GhostList<K, V> ghosts;
    private final int a1InTarget;
    private final int a1OutTarget;

    /** Marks the key that {@link #beforeInsert} just found in the ghost list, if any. */
    private K pendingGhostHit;

    /** A 2Q for a cache of {@code capacity} entries, with the paper's 25% / 50% split. */
    public TwoQueuePolicy(int capacity) {
        this(capacity, 0.25, 0.5);
    }

    /**
     * @param capacity       the cache's entry capacity, which sizes the two targets below
     * @param a1InFraction   target size of A1in as a fraction of capacity, in (0, 1)
     * @param a1OutFraction  target size of the A1out ghost list as a fraction of capacity, in [0, 1)
     */
    public TwoQueuePolicy(int capacity, double a1InFraction, double a1OutFraction) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        if (!(a1InFraction > 0 && a1InFraction < 1)) {
            throw new IllegalArgumentException("a1InFraction must be in (0, 1), got " + a1InFraction);
        }
        if (!(a1OutFraction >= 0 && a1OutFraction < 1)) {
            throw new IllegalArgumentException("a1OutFraction must be in [0, 1), got " + a1OutFraction);
        }
        this.a1InTarget = Math.max(1, (int) (capacity * a1InFraction));
        this.a1OutTarget = (int) (capacity * a1OutFraction);
        this.ghosts = new GhostList<>(Math.max(4, this.a1OutTarget));
    }

    @Override
    public void beforeInsert(K key) {
        Node<K, V> ghost = ghosts.find(key, Hashing.spread(key));
        if (ghost != null) {
            ghosts.remove(ghost);
            pendingGhostHit = key;
        }
    }

    @Override
    public void onInsert(Node<K, V> node) {
        if (Objects.equals(pendingGhostHit, node.key())) {
            // A key that survived being forgotten and was asked for again: it has
            // earned Am directly, skipping A1in entirely.
            node.setSegment(MAIN);
            main.addToHead(node);
        } else {
            node.setSegment(A1_IN);
            a1in.addToHead(node);          // FIFO: the tail is the oldest, and the next victim
        }
        pendingGhostHit = null;
    }

    @Override
    public void onAccess(Node<K, V> node) {
        if (node.segment() == MAIN) {
            main.moveToHead(node);
        }
        // A hit on an A1in entry is deliberately a no-op: see the class documentation.
    }

    @Override
    public void onEvict(Node<K, V> victim) {
        if (victim.segment() == A1_IN) {
            ghosts.addNewest(victim.key(), victim.hash());
            while (ghosts.size() > a1OutTarget) {
                ghosts.removeOldest();
            }
        }
    }

    @Override
    public void onRemove(Node<K, V> node) {
        segmentOf(node).unlink(node);
    }

    @Override
    public Node<K, V> selectVictim() {
        if (a1in.size() > a1InTarget) {
            Node<K, V> fromA1in = a1in.tail();
            if (fromA1in != null) {
                return fromA1in;
            }
        }
        Node<K, V> fromMain = main.tail();
        return fromMain != null ? fromMain : a1in.tail();
    }

    @Override
    public void clear() {
        a1in.clear();
        main.clear();
        ghosts.clear();
        pendingGhostHit = null;
    }

    @Override
    public String name() {
        return "2Q";
    }

    private IntrusiveLinkedList<K, V> segmentOf(Node<K, V> node) {
        return node.segment() == MAIN ? main : a1in;
    }

    /** @return whether {@code key} is currently remembered as a ghost (recently evicted from A1in) */
    public boolean isGhost(K key) {
        return ghosts.find(key, Hashing.spread(key)) != null;
    }

    /** @return keys in A1in, newest first */
    public java.util.List<K> a1inKeys() {
        return a1in.keysFromMruToLru();
    }

    /** @return keys in Am, most recently used first */
    public java.util.List<K> mainKeys() {
        return main.keysFromMruToLru();
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        a1in.assertInvariants();
        main.assertInvariants();
        ghosts.assertInvariants();
        Invariants.check(a1in.size() + main.size() == expectedEntryCount,
                "2Q tracks " + (a1in.size() + main.size()) + " entries but the cache holds " + expectedEntryCount);
        Invariants.check(ghosts.size() <= a1OutTarget,
                "the A1out ghost list holds " + ghosts.size() + " keys, above its target of " + a1OutTarget);
        a1in.forEach(node -> Invariants.check(node.segment() == A1_IN,
                node.key() + " is on the A1in list but is tagged main"));
        main.forEach(node -> Invariants.check(node.segment() == MAIN,
                node.key() + " is on the main list but is tagged A1in"));
    }
}
