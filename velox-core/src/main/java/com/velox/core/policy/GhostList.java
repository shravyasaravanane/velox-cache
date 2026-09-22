package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.structure.OpenAddressingMap;
import com.velox.core.util.Invariants;

/**
 * A remembered list of <b>keys that were recently evicted</b>: a "ghost" directory.
 *
 * <h2>Why a cache would remember what it threw away</h2>
 *
 * When a policy evicts an entry it is making a bet: "you will not be wanted soon". The
 * only way to find out whether the bet was wrong is to notice when the key comes back.
 * So 2Q, ARC and LRU-K keep the <i>keys</i> (never the values, which is what makes it
 * cheap) of recent victims. A miss on a ghost is evidence the last eviction was a
 * mistake, and the policy uses it: 2Q admits the key straight to its protected queue,
 * ARC shifts its balance towards whichever side made the mistake, LRU-K resumes the
 * key's access history.
 *
 * <h2>Structure</h2>
 *
 * A ghost is a {@link Node} with a key and no value. The same two structures the cache
 * itself is built from hold them: an {@link OpenAddressingMap} for "is this key a
 * ghost?" in O(1), and an {@link IntrusiveLinkedList} for age order, so the oldest ghost
 * can be forgotten in O(1). The list is newest-first: the tail is the oldest.
 *
 * <p>The list does not bound itself. Each policy has its own rule for how many ghosts
 * to keep (2Q: half the cache; ARC: an equation involving two lists), so the caller
 * trims.
 *
 * @param <K> the key type
 * @param <V> the value type of the cache this belongs to (ghosts hold none)
 */
final class GhostList<K, V> {

    private final OpenAddressingMap<K, V> index;
    private final IntrusiveLinkedList<K, V> order = new IntrusiveLinkedList<>();

    GhostList(int expectedEntries) {
        this.index = new OpenAddressingMap<>(Math.max(4, expectedEntries));
    }

    /** @return the ghost for {@code key}, or {@code null} if the key is not remembered. O(1) */
    Node<K, V> find(K key, int hash) {
        return index.get(key, hash);
    }

    /**
     * Remembers {@code key} as the newest ghost.
     *
     * @return the ghost, so the caller can attach data to it (LRU-K stores a history)
     * @throws IllegalStateException if the key is already a ghost: a key is either
     *                               remembered or it is not
     */
    Node<K, V> addNewest(K key, int hash) {
        Node<K, V> ghost = new Node<>(key, null, hash);
        Node<K, V> previous = index.put(ghost);
        if (previous != null) {
            throw new IllegalStateException("key is already a ghost: " + key);
        }
        order.addToHead(ghost);
        return ghost;
    }

    /** Forgets {@code ghost}. O(1) */
    void remove(Node<K, V> ghost) {
        order.unlink(ghost);
        index.remove(ghost.key(), ghost.hash());
    }

    /** @return the oldest ghost, or {@code null} if there are none. O(1) */
    Node<K, V> oldest() {
        return order.tail();
    }

    /** Forgets the oldest ghost, if any. O(1) */
    void removeOldest() {
        Node<K, V> oldest = order.tail();
        if (oldest != null) {
            remove(oldest);
        }
    }

    int size() {
        return order.size();
    }

    /** @return every ghost key, newest first -- tests and dashboards only */
    java.util.List<K> keysNewestFirst() {
        return order.keysFromMruToLru();
    }

    void clear() {
        order.clear();
        index.clear();
    }

    /** Both structures must describe exactly the same set of ghosts. */
    void assertInvariants() {
        if (!Invariants.ENABLED) {
            return;
        }
        order.assertInvariants();
        index.assertInvariants();
        Invariants.check(order.size() == index.size(),
                "ghost list has " + order.size() + " entries but its index has " + index.size());
        order.forEach(ghost -> Invariants.check(index.get(ghost.key(), ghost.hash()) == ghost,
                "ghost " + ghost.key() + " is in the age list but not in the index"));
    }
}
