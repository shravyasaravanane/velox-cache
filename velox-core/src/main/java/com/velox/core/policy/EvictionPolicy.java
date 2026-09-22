package com.velox.core.policy;

import com.velox.core.structure.Node;

/**
 * Decides which entry leaves when the cache is full — and, optionally,
 * whether a new entry deserves to come in at all.
 *
 * <h2>The separation this interface creates</h2>
 *
 * The cache owns <b>storage</b>: the hash table, the entries, the size limit.
 * A policy owns <b>order</b>: which entry is most expendable right now.
 *
 * <p>Keeping those apart is the most important architectural decision in the
 * project. It is what lets us run ten different algorithms over the same
 * engine, race them against each other on live traffic, and swap the policy
 * at runtime without touching the cache. A textbook LRU with the ordering
 * logic baked into the cache class can do none of that.
 *
 * <h2>Admission vs eviction — the idea most people miss</h2>
 *
 * Notice there are <b>two</b> decisions here, not one:
 *
 * <ul>
 *   <li>{@link #selectVictim()} — <i>who leaves?</i></li>
 *   <li>{@link #admit(Node, Node)} — <i>should the newcomer even come in?</i></li>
 * </ul>
 *
 * Every classical policy (LRU, LFU, FIFO, CLOCK, ARC) answers only the first.
 * They admit everything unconditionally and argue only about the victim.
 *
 * <p>That is exactly why LRU scores <b>0%</b> on a looping scan. With capacity
 * 3 and requests {@code 1,2,3,4,1,2,3,4,...}, every key is evicted on the
 * request immediately before it is needed again. A policy that simply kept
 * 1, 2, 3 and <i>refused to admit</i> 4 would score 75% — doing less work for
 * a far better result. LRU cannot do that, because it has no way to say no.
 *
 * <p>Real web traffic is full of "one-hit wonders": keys requested exactly
 * once, ever. Letting them evict a key the cache has served fifty times is
 * the main way LRU loses hit ratio. W-TinyLFU (Tier 3) is the policy that
 * overrides {@link #admit} — and that single method is where essentially all
 * of its advantage comes from.
 *
 * <h2>Implementation contract</h2>
 *
 * <ul>
 *   <li>Every method must be <b>O(1)</b>, with LRU-K the one documented
 *       exception at O(log n).</li>
 *   <li>Implementations are <b>not thread-safe</b>. The shard that owns the
 *       policy holds the lock (Tier 2).</li>
 *   <li>{@link #onAccess} runs on every cache hit, so it is the hottest code
 *       in the system. Keep it to a handful of pointer writes.</li>
 * </ul>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface EvictionPolicy<K, V> {

    /**
     * A new entry was just stored. Start tracking it.
     *
     * @param node the newly inserted node
     * @implSpec O(1)
     */
    void onInsert(Node<K, V> node);

    /**
     * An existing entry was just read or overwritten — "this was used".
     *
     * <p>The hottest method in the cache. LRU moves the node to the head of
     * its list; LFU bumps a frequency counter; CLOCK just flips a bit.
     *
     * @param node the node that was accessed
     * @implSpec O(1)
     */
    void onAccess(Node<K, V> node);

    /**
     * An entry is leaving — evicted, invalidated, or expired. Drop any
     * metadata the policy holds for it.
     *
     * <p>Forgetting to implement this leaks: the node stays linked into the
     * policy's structures forever, so the list keeps growing while the cache
     * reports a steady size. Our invariant checks compare the two counts
     * precisely to catch that.
     *
     * @param node the node being removed
     * @implSpec O(1)
     */
    void onRemove(Node<K, V> node);

    /**
     * Chooses the entry to discard next. Does <b>not</b> remove it — the
     * cache calls {@link #onRemove} for that. It <i>may</i> reorganise the policy's
     * own lists (CLOCK clears reference bits as its hand sweeps; W-TinyLFU moves the
     * duel's winner into the main region).
     *
     * @return the most expendable entry, or {@code null} if nothing is tracked
     * @implSpec O(1)
     */
    Node<K, V> selectVictim();

    /**
     * Admission control: may {@code candidate} enter at the cost of evicting
     * {@code victim}?
     *
     * <p>The default is "yes, always", which is what every classical policy
     * does. Returning {@code false} discards the candidate and leaves the
     * cache untouched — the new value is simply not cached.
     *
     * @param candidate the entry hoping to be admitted
     * @param victim    the entry that would be evicted to make room
     * @return {@code true} to admit, {@code false} to discard the candidate
     * @implSpec O(1)
     */
    default boolean admit(Node<K, V> candidate, Node<K, V> victim) {
        return true;
    }

    /**
     * A NEW key is about to be inserted, before the cache makes room for it.
     *
     * <p>This is the hook for policies whose choice of victim depends on <i>who is
     * arriving</i>. ARC is the example: when the arriving key is one it recently
     * evicted, that is evidence its balance between "recent" and "frequent" entries was
     * wrong, and it retunes itself <i>before</i> choosing whom to evict. Called whether
     * or not a {@link #onMiss} preceded it, so it must tolerate seeing the same key
     * twice (a cache-aside caller misses, then stores). Not called for a value too
     * large to ever fit.
     *
     * @param key the key that is about to be stored
     * @implSpec O(1)
     */
    default void beforeInsert(K key) {
        // Most policies do not care who is arriving.
    }

    /**
     * {@code victim} is about to be removed <b>to make room</b>, and {@link #onRemove}
     * will follow immediately.
     *
     * <p>{@code onRemove} cannot tell a capacity eviction from an invalidation or an
     * expiry, and the difference matters to policies with ghost lists: only an entry
     * that was evicted because there was no room is worth remembering. Being pushed
     * out is evidence about the policy; being deleted by the application is not.
     *
     * @param victim the entry chosen by {@link #selectVictim()} and not refused by
     *               {@link #admit}
     * @implSpec O(1)
     */
    default void onEvict(Node<K, V> victim) {
        // Only ghost-list policies remember who was evicted.
    }

    /**
     * A lookup missed.
     *
     * <p>Most policies ignore this. It exists for the policies that keep
     * <b>ghost lists</b> — ARC and 2Q remember the keys of entries they
     * recently evicted, so that a miss on a ghost is evidence the previous
     * eviction was a mistake, and the policy retunes itself. Those ghosts
     * hold keys only, never values, which is why remembering them is cheap.
     *
     * @param key the key that was not found
     * @implSpec O(1)
     */
    default void onMiss(K key) {
        // Most policies have nothing to learn from a miss.
    }

    /** Discards all tracking state. @implSpec O(n) */
    void clear();

    /** @return a short display name, e.g. {@code "LRU"} */
    String name();

    /**
     * Verifies the policy's internal structures are consistent.
     *
     * @param expectedEntryCount how many entries the cache currently holds;
     *                           the policy must be tracking exactly this many
     * @throws IllegalStateException if the policy's state is corrupt
     */
    default void assertInvariants(int expectedEntryCount) {
        // Optional; implementations with internal structures should override.
    }
}
