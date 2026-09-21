package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.util.Invariants;

/**
 * Least Recently Used — evict whatever has gone longest without being touched.
 *
 * <h2>The assumption LRU makes</h2>
 *
 * LRU bets on <b>temporal locality</b>: something used recently is likely to
 * be used again soon. If nobody has asked for a key in an hour, probably
 * nobody will ask in the next minute either, so it is the safest thing to
 * throw away.
 *
 * <p>That bet is usually good. Real traffic is bursty and repetitive, and on
 * most workloads LRU is a solid default — which is why it is the textbook
 * answer and what this project's brief asks for.
 *
 * <h2>Where the bet fails</h2>
 *
 * LRU knows <i>when</i> an entry was last used and nothing else. It has no
 * idea <i>how often</i>. So a key requested a thousand times today loses to
 * one requested once five seconds ago.
 *
 * <p>And it admits everything unconditionally, which is fatal on a scan.
 * Capacity 3, requests {@code 1,2,3,4,1,2,3,4,...}: every key is evicted on
 * the request immediately before it is needed again, forever.
 * <b>Hit ratio: exactly 0%.</b> A policy that kept 1, 2, 3 and refused 4
 * would score 75%.
 *
 * <p>Those two failures are what Tier 3's policies exist to fix. Build LRU
 * first, measure it honestly, then beat it.
 *
 * <h2>Implementation</h2>
 *
 * All the work lives in {@link IntrusiveLinkedList}. Because the cache's hash
 * map stores {@code key -> Node} and the node carries its own list pointers,
 * this class never searches for anything — every operation is a few pointer
 * writes.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class LruPolicy<K, V> implements EvictionPolicy<K, V> {

    /** Recency order: most recently used at the head, least at the tail. */
    private final IntrusiveLinkedList<K, V> recency = new IntrusiveLinkedList<>();

    @Override
    public void onInsert(Node<K, V> node) {
        recency.addToHead(node);            // brand new = most recently used
    }

    @Override
    public void onAccess(Node<K, V> node) {
        recency.moveToHead(node);           // just used = most recently used
    }

    @Override
    public void onRemove(Node<K, V> node) {
        recency.unlink(node);
    }

    @Override
    public Node<K, V> selectVictim() {
        return recency.tail();              // the tail IS the LRU entry
    }

    @Override
    public void clear() {
        recency.clear();
    }

    @Override
    public String name() {
        return "LRU";
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        recency.assertInvariants();

        // The strongest check we have: every entry in the cache's hash map
        // must also be in this list, and nothing else may be.
        //
        // A mismatch means the two structures have drifted apart — usually a
        // path through the cache that removes from one and forgets the other.
        // That bug does not crash; it silently leaks entries or evicts live
        // ones. Comparing the counts catches it on the very next operation.
        Invariants.check(recency.size() == expectedEntryCount,
                "LRU is tracking " + recency.size() + " entries but the cache holds "
                        + expectedEntryCount + " — the map and the recency list have drifted apart");
    }

    /** @return the keys in recency order, MRU first. Tests and debugging only. */
    public java.util.List<K> keysFromMruToLru() {
        return recency.keysFromMruToLru();
    }
}
