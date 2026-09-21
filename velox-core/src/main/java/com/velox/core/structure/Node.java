package com.velox.core.structure;

/**
 * A single entry stored in the cache.
 *
 * <h2>Why this class is "intrusive"</h2>
 *
 * A normal linked list wraps your data in separate cell objects:
 *
 * <pre>
 *   Cell{next} -> Cell{next} -> Cell{next}
 *      |            |            |
 *    value        value        value
 * </pre>
 *
 * An <b>intrusive</b> list puts the {@code prev}/{@code next} pointers
 * <i>inside the data object itself</i>. There are no wrapper cells: the node
 * IS the entry.
 *
 * <p>That matters enormously here. Our hash map stores {@code key -> Node}.
 * So when a cache hit happens, the map hands us the node directly, and the
 * node already knows its own neighbours. We can unlink it and move it to the
 * front of the recency list in <b>O(1)</b> — with no searching at all.
 *
 * <p>If instead we stored {@code key -> value} and kept a separate list of
 * keys, then on every hit we would have to <i>scan the list</i> to find where
 * that key sits before we could move it. That is <b>O(n)</b>, and it would
 * break the assignment's "constant time" requirement.
 *
 * <h2>A warning about mutability</h2>
 *
 * The {@code prev}/{@code next} pointers are deliberately package-private.
 * Only classes inside {@code com.velox.core.structure} may touch them, which
 * keeps the "who is able to corrupt the list" surface as small as possible —
 * one package, a few hundred lines, all covered by invariant checks.
 *
 * <p>{@link #value} is different: it is ordinary data, not structure, so it
 * is safe to expose through {@link #value()} and {@link #setValue}. Getting
 * a value wrong loses one cache entry; getting a pointer wrong corrupts the
 * entire list.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class Node<K, V> {

    /** The cache key. Never changes once the node is created. */
    final K key;

    /** The cached value. Mutable: a put() on an existing key overwrites it. */
    V value;

    /**
     * The key's hash code, computed once and stored.
     *
     * <p>Why cache it? Because we use the hash in several places (choosing a
     * bucket, comparing entries, later choosing a shard). Recomputing
     * {@code key.hashCode()} each time could be expensive — for a String it
     * walks every character. Computing it once trades 4 bytes for speed.
     */
    final int hash;

    /**
     * The previous node in the recency list (towards the most recently used).
     *
     * <p>This single field is the reason we use a <b>doubly</b> linked list.
     * To unlink a node in O(1) we must tell its predecessor to skip over it
     * ({@code prev.next = this.next}). Without a {@code prev} pointer we
     * would have to scan from the head to find the predecessor — O(n).
     */
    Node<K, V> prev;

    /** The next node in the recency list (towards the least recently used). */
    Node<K, V> next;

    // ------------------------------------------------------------------
    //  Per-policy bookkeeping
    //
    //  Different eviction policies need different scraps of metadata on each
    //  entry. We store them on the node itself rather than in side tables
    //  (a HashMap<Node, Integer>, say) for the same reason the list pointers
    //  live here: a side table costs a hash lookup per access, while a field
    //  on the node we are already holding costs nothing.
    //
    //  The price is a few bytes per entry even for policies that ignore them.
    //  Only ONE policy ever tracks a given node, so these fields are shared
    //  by whichever policy owns it -- never by two at once.
    // ------------------------------------------------------------------

    /** LFU: how many times this entry has been used since it was inserted (or last aged). */
    int frequency;

    /** CLOCK: the "second chance" bit. Set on every hit; cleared as the clock hand passes. */
    boolean referenced;

    /** Array-backed policies (Random): this node's index in the policy's array. -1 = untracked. */
    int slot = -1;

    /** Free-form pointer for a policy's own structures, e.g. the frequency bucket an LFU entry sits in. */
    Object policyData;

    /**
     * Creates a data-carrying node.
     *
     * @param key   the cache key (must not be null)
     * @param value the cached value
     * @param hash  the pre-computed, spread hash of the key
     */
    public Node(K key, V value, int hash) {
        this.key = key;
        this.value = value;
        this.hash = hash;
    }

    /**
     * Creates an empty <b>sentinel</b> node.
     *
     * <p>Sentinels are permanent dummy nodes that sit at each end of the list
     * and never hold data. Their only job is to guarantee that every real
     * node always has a non-null {@code prev} and {@code next}.
     *
     * <p>That guarantee is worth more than it sounds. Without sentinels,
     * removing a node means handling "it's the first one", "it's the last
     * one" and "it's the only one" as separate special cases — three extra
     * branches and three chances to write a null-pointer bug. With sentinels,
     * unlinking is four unconditional pointer writes, always.
     */
    Node() {
        this.key = null;
        this.value = null;
        this.hash = 0;
    }

    /** @return this entry's key. */
    public K key() {
        return key;
    }

    /**
     * @return the pre-computed spread hash of this entry's key
     * @see com.velox.core.util.Hashing#spread(Object)
     */
    public int hash() {
        return hash;
    }

    /** @return this entry's current value. */
    public V value() {
        return value;
    }

    /** @return LFU use count. Meaningful only while an LFU policy tracks this node. */
    public int frequency() {
        return frequency;
    }

    /** @param frequency the new LFU use count */
    public void setFrequency(int frequency) {
        this.frequency = frequency;
    }

    /** @return the CLOCK reference bit. */
    public boolean isReferenced() {
        return referenced;
    }

    /** @param referenced the new CLOCK reference bit */
    public void setReferenced(boolean referenced) {
        this.referenced = referenced;
    }

    /** @return this node's index in an array-backed policy, or -1 if untracked. */
    public int slot() {
        return slot;
    }

    /** @param slot the new array index, or -1 to mark the node untracked */
    public void setSlot(int slot) {
        this.slot = slot;
    }

    /** @return the owning policy's private data for this node, or {@code null}. */
    public Object policyData() {
        return policyData;
    }

    /** @param policyData the owning policy's private data for this node */
    public void setPolicyData(Object policyData) {
        this.policyData = policyData;
    }

    /**
     * Overwrites this entry's value.
     *
     * <p>Used when {@code put()} is called for a key that is already cached.
     * We update the existing node in place rather than removing it and
     * inserting a fresh one: that avoids an allocation, avoids two hash-table
     * operations, and — importantly — keeps the node's position in whatever
     * policy structures already reference it.
     *
     * @param value the new value
     */
    public void setValue(V value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }
}
