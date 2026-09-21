package com.velox.core;

import com.velox.core.policy.EvictionPolicy;
import com.velox.core.stats.CacheStats;
import com.velox.core.stats.StatsCounter;
import com.velox.core.structure.Node;
import com.velox.core.structure.OpenAddressingMap;
import com.velox.core.util.Hashing;
import com.velox.core.util.Invariants;

import java.util.Objects;
import java.util.function.Function;

/**
 * The cache engine: a bounded map with a pluggable eviction policy.
 *
 * <h2>How the two structures cooperate</h2>
 *
 * <pre>
 *        OpenAddressingMap                 EvictionPolicy (LRU)
 *        "where is this key?"              "what is most expendable?"
 *              O(1)                                O(1)
 *
 *          "A" ──────────┐
 *          "B" ────────┐ │
 *          "C" ──────┐ │ │
 *                    │ │ │
 *                    ▼ ▼ ▼
 *         head &lt;-&gt; [C] &lt;-&gt; [B] &lt;-&gt; [A] &lt;-&gt; tail
 *                  MRU                 LRU
 *                                       ▲
 *                                 evicted from here
 * </pre>
 *
 * <p><b>Both structures hold the very same {@link Node} objects.</b> Neither
 * can do the job alone: a hash map has no ordering, and a linked list has no
 * fast lookup. Together they give O(1) for everything the brief asks for.
 *
 * <p>The consequence to keep in mind: every entry lives in two places at
 * once, so every code path must update both. Removing from the map but
 * forgetting the policy leaks; removing from the policy but forgetting the
 * map leaves a phantom. That is what
 * {@link EvictionPolicy#assertInvariants(int)} checks, by comparing the two
 * counts after every operation in our tests.
 *
 * <h2>Thread safety</h2>
 *
 * <b>None.</b> This class is single-threaded, deliberately, and that is
 * correct for Tier 0.
 *
 * <p>Note {@link #getIfPresent} calls {@code policy.onAccess()} — so a
 * <i>read</i> mutates shared structure. Harmless with one thread; with
 * sixteen it means every read needs an exclusive lock, and throughput falls
 * as threads are added. Tier 2 fixes that with sharding and lossy read
 * buffers. Understanding why the problem exists is the point of building it
 * this way first.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class VeloxCache<K, V> implements Cache<K, V> {

    /** Key to node. Answers "where is it?" in O(1). */
    private final OpenAddressingMap<K, V> data;

    /** Answers "what should leave?" in O(1). */
    private final EvictionPolicy<K, V> policy;

    /** Hard limit on entries. */
    private final int maximumSize;

    private final StatsCounter stats = new StatsCounter();

    /**
     * @param maximumSize the most entries to hold; must be at least 1
     * @param policy      the eviction strategy
     */
    public VeloxCache(int maximumSize, EvictionPolicy<K, V> policy) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be at least 1, got " + maximumSize);
        }
        this.maximumSize = maximumSize;
        this.policy = Objects.requireNonNull(policy, "policy");
        // Size the table for the full capacity up front. The cache will run at
        // its limit essentially forever, so pre-sizing avoids resizing later.
        this.data = new OpenAddressingMap<>(maximumSize);
    }

    // ------------------------------------------------------------------
    //  Reads
    // ------------------------------------------------------------------

    @Override
    public V getIfPresent(K key) {
        Objects.requireNonNull(key, "key");
        int hash = Hashing.spread(key);

        Node<K, V> node = data.get(key, hash);

        if (node == null) {
            stats.recordMiss();
            // Tell the policy about the miss. LRU ignores it; ARC and 2Q use
            // it to check their ghost lists and retune themselves (Tier 3).
            policy.onMiss(key);
            return null;
        }

        // The entry was used, so its position in the eviction order changes.
        // This is the line that makes a read a write.
        policy.onAccess(node);
        stats.recordHit();
        return node.value();
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(loader, "loader");

        V cached = getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        // MISS: compute the value and cache it.
        //
        // Note what is missing here. If a thousand threads miss on the same
        // hot key at the same instant, this runs the loader a thousand times
        // for one database row — a "cache stampede", and a classic way a
        // caching layer takes down the system it was meant to protect.
        // Tier 1 adds single-flight coalescing so only one loader runs and
        // the rest wait for its result.
        stats.recordLoad();
        V loaded = loader.apply(key);
        if (loaded != null) {
            put(key, loaded);
        }
        return loaded;
    }

    @Override
    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key");
        // Deliberately does NOT call policy.onAccess: asking whether something
        // is cached is not the same as using it, and counting it as a use
        // would distort the eviction order and the hit statistics.
        return data.containsKey(key, Hashing.spread(key));
    }

    // ------------------------------------------------------------------
    //  Writes
    // ------------------------------------------------------------------

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        int hash = Hashing.spread(key);

        // --- Case 1: the key is already cached. Update in place. ---
        Node<K, V> existing = data.get(key, hash);
        if (existing != null) {
            existing.setValue(value);
            policy.onAccess(existing);   // a write counts as a use
            return;
        }

        // --- Case 2: a new key. We may need to make room first. ---
        Node<K, V> candidate = new Node<>(key, value, hash);

        if (data.size() >= maximumSize) {
            Node<K, V> victim = policy.selectVictim();

            if (victim != null) {
                // Admission control. LRU always says yes. W-TinyLFU (Tier 3)
                // compares the two entries' estimated frequencies and may
                // refuse the newcomer outright — which is how it survives
                // scans that take LRU's hit ratio to zero.
                if (!policy.admit(candidate, victim)) {
                    stats.recordRejection();
                    return;              // candidate discarded; cache unchanged
                }
                evict(victim);
            }
        }

        data.put(candidate);
        policy.onInsert(candidate);
    }

    @Override
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> removed = data.remove(key, Hashing.spread(key));
        if (removed != null) {
            policy.onRemove(removed);
        }
        // Deliberately not counted as an eviction: the caller asked for this,
        // it is not a symptom of memory pressure. See RemovalCause.
    }

    @Override
    public void invalidateAll() {
        data.clear();
        policy.clear();
    }

    /**
     * Discards {@code victim} because the cache is full.
     *
     * <p>Both structures must be updated, in this order. Removing from the
     * map first means that even if something went wrong afterwards, the entry
     * is already unreachable to callers — the cache is never seen serving an
     * entry it has decided to drop.
     */
    private void evict(Node<K, V> victim) {
        data.remove(victim.key(), victim.hash());
        policy.onRemove(victim);
        stats.recordEviction();
    }

    // ------------------------------------------------------------------
    //  Introspection
    // ------------------------------------------------------------------

    @Override
    public int size() {
        return data.size();
    }

    @Override
    public int maximumSize() {
        return maximumSize;
    }

    @Override
    public CacheStats stats() {
        return stats.snapshot();
    }

    /** @return the name of the active eviction policy, e.g. {@code "LRU"} */
    public String policyName() {
        return policy.name();
    }

    /**
     * Verifies the cache is internally consistent. Does nothing unless
     * {@code -Dvelox.assertions=true}.
     *
     * @throws IllegalStateException if the cache is in an impossible state
     */
    public void assertInvariants() {
        if (!Invariants.ENABLED) {
            return;
        }
        Invariants.check(data.size() <= maximumSize,
                "cache holds " + data.size() + " entries, over its limit of " + maximumSize);
        data.assertInvariants();
        // This compares the policy's entry count against the map's, which is
        // what catches the two structures drifting apart.
        policy.assertInvariants(data.size());
    }

    @Override
    public String toString() {
        return "VeloxCache[" + policy.name() + ", " + size() + "/" + maximumSize + ", " + stats() + "]";
    }
}
