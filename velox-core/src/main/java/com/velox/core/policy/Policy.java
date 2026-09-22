package com.velox.core.policy;

import java.util.function.IntFunction;

/**
 * The catalogue of available eviction policies.
 *
 * <p>This enum is what makes the policy swappable by name -- from a config
 * file, a REST call, or a benchmark's command line -- without the caller
 * knowing any implementation classes.
 *
 * <p>Adding a policy means adding a class and one line here; nothing in the
 * cache engine changes. That is the payoff of the {@link EvictionPolicy}
 * abstraction, and Tier 1 proves it: four policies were added without editing
 * {@code VeloxCache} at all.
 *
 * <h2>Why the factory takes the capacity</h2>
 *
 * Some policies need to size themselves relative to the cache. LFU-with-aging
 * halves its counts every ~10x capacity requests; W-TinyLFU (Tier 3) sizes its
 * window and sketch from capacity. So every factory receives the maximum
 * size, even though most ignore it.
 */
public enum Policy {

    /**
     * Least Recently Used. The textbook default and what the project brief asks
     * for. Strong on recency locality; scores 0% on a loop one larger than the cache.
     */
    LRU("LRU", capacity -> new LruPolicy<>()),

    /**
     * First In, First Out. The floor every other policy must beat. Hits change
     * nothing, so reads are read-only.
     */
    FIFO("FIFO", capacity -> new FifoPolicy<>()),

    /**
     * Uniformly random eviction. Zero metadata, and the one policy that does
     * well on a slightly-too-big loop, where the deterministic ones score 0%.
     */
    RANDOM("RANDOM", capacity -> new RandomPolicy<>()),

    /**
     * CLOCK / second-chance. An LRU approximation where a hit is a single bit
     * write, which is why real buffer pools use it.
     */
    CLOCK("CLOCK", capacity -> new ClockPolicy<>()),

    /**
     * Least Frequently Used in O(1), with no aging. Excellent on a stable
     * popularity distribution; never forgets, so it is poor when the hot set moves.
     */
    LFU("LFU", capacity -> new LfuPolicy<>()),

    /**
     * LFU that halves every count once per ~10x capacity requests, letting a
     * formerly hot key be overtaken when the workload shifts.
     */
    LFU_AGED("LFU-AGED", capacity -> new LfuPolicy<>(10L * capacity)),

    /**
     * Segmented LRU: two LRU lists, probation and protected. A second use promotes an
     * entry out of probation, so a long scan of one-hit keys can only cycle through
     * probation and never reaches (or evicts) anything used more than once.
     */
    SLRU("SLRU", capacity -> new SlruPolicy<>(capacity)),

    /**
     * 2Q (Johnson &amp; Shasha): A1in (FIFO), A1out (a ghost list of recent A1in
     * evictions) and Am (LRU). Stricter than {@link #SLRU}: a hit while still in A1in
     * does nothing, so promotion to Am requires surviving an eviction and being asked
     * for again, not merely a second touch in quick succession.
     */
    TWO_Q("2Q", capacity -> new TwoQueuePolicy<>(capacity)),

    /**
     * LRU-K with K=2: an entry is judged by recency only after its <b>second</b>
     * reference, not its first. Until then it is more evictable than anything that has
     * ever reached two references, however long ago. See {@link LruKPolicy}.
     */
    LRU_K("LRU-2", capacity -> new LruKPolicy<>(2)),

    /**
     * Adaptive Replacement Cache: like {@link #TWO_Q}, but the split between the recency
     * and frequency sides is not a fixed fraction -- it is tuned continuously from the
     * cache's own ghost-list hit rate. See {@link ArcPolicy}.
     */
    ARC("ARC", capacity -> new ArcPolicy<>(capacity));

    private final String displayName;
    private final IntFunction<EvictionPolicy<?, ?>> factory;

    Policy(String displayName, IntFunction<EvictionPolicy<?, ?>> factory) {
        this.displayName = displayName;
        this.factory = factory;
    }

    /**
     * Builds a fresh policy instance.
     *
     * <p>The unchecked cast is safe because every policy is generic over
     * {@code <K, V>} and the factory creates an empty instance -- there is no
     * key or value of the wrong type inside it to go wrong.
     *
     * @param maximumSize the cache capacity, for policies that size themselves from it
     * @param <K> the key type
     * @param <V> the value type
     * @return a new, empty policy
     */
    @SuppressWarnings("unchecked")
    public <K, V> EvictionPolicy<K, V> create(int maximumSize) {
        return (EvictionPolicy<K, V>) factory.apply(maximumSize);
    }

    /** @return the human-readable name, e.g. for dashboards and reports */
    public String displayName() {
        return displayName;
    }
}
