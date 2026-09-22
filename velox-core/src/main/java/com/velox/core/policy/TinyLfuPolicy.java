package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.util.Invariants;

/**
 * <b>W-TinyLFU</b> (Einziger, Friedman &amp; Manes, 2017): the policy Caffeine actually ships
 * with, and the most advanced one in this project.
 *
 * <h2>The problem with a frequency count that only knows what is resident</h2>
 *
 * {@link LfuPolicy} counts frequency for free, because it only ever counts entries the cache
 * is already storing. But that means it can never compare a brand-new candidate against the
 * entry it would evict — the candidate has no count yet. W-TinyLFU's answer is
 * {@link CountMinSketch}: an approximate frequency count for <b>every key ever seen</b>,
 * resident or not, in a fixed, small amount of memory, gated by a {@link BloomFilter}
 * "doorkeeper" so a key's very first appearance does not even cost a sketch slot (see
 * {@link #recordUse}).
 *
 * <h2>Two regions, and a window that protects new arrivals from the sketch</h2>
 *
 * <ul>
 *   <li><b>window</b> (about 1% of capacity) — a plain LRU. Every new key lands here,
 *       unconditionally, however low its estimated frequency is. This is deliberate: a
 *       genuinely new hot key needs somewhere to prove itself before it has any track
 *       record, and the sketch cannot vouch for something it has never seen.</li>
 *   <li><b>main</b> (the rest) — {@link SlruPolicy}'s own probation/protected split, run
 *       independently here rather than delegated to an {@code SlruPolicy} instance, so this
 *       class owns one consistent three-way tag on {@link Node#segment()} instead of two
 *       policies disagreeing about what that field means.</li>
 * </ul>
 *
 * <h2>The duel</h2>
 *
 * When the window is over its own target size, its least recently used entry becomes a
 * <b>candidate</b> for promotion into main. If main has spare room, the candidate is admitted
 * for free. If main is also full, the candidate's sketch estimate is compared against main's
 * own outgoing entry (from probation, or protected if probation is empty): whichever is
 * higher survives, and the other leaves the cache. A tie favours the incumbent — a newcomer
 * must strictly outscore the entry it would displace.
 *
 * <p>All of this happens inside {@link #selectVictim()}, not {@link #admit}: see that
 * method's own documentation, and {@link EvictionPolicy}'s, for why the duel does not fit
 * the {@code admit(candidate, victim)} shape at all. The newly arriving key (whatever
 * triggered this eviction in the first place) is never a party to the duel — it always
 * enters the window unconditionally, via {@link #onInsert}.
 *
 * <h2>An honest finding: the duel is unreachable on a plain count-bounded cache</h2>
 *
 * Every entry weighs 1 on a count-bounded cache, so {@code window.size() + main.size()}
 * always equals the cache's true resident count, and a victim is only ever requested once
 * that count already equals capacity (which equals {@code windowCapacity + mainCapacity} by
 * construction). Working through the arithmetic: whenever {@link #selectVictim()} is called,
 * window's excess over its target and main's spare room are exact complements of each other,
 * so the free-admission loop always absorbs the <i>entire</i> excess before the duel check is
 * reached. The duel only becomes reachable when the cache's true resident count can differ
 * from what this policy was sized for — a <b>weighted</b> cache, where {@code Capacity}'s own
 * {@code expectedEntries} hint is exactly that: a hint, not a guarantee. This is not a
 * corner case invented for a test: any weighted cache built with this policy can hit it in
 * ordinary use.
 *
 * <h2>Why one-hit wonders lose, on the common (count-bounded) cache</h2>
 *
 * With the duel unreachable, the mechanism is the same one {@link SlruPolicy} uses: a key
 * requested exactly once rides the free-admission cascade into probation on nothing but
 * recency, exactly like every other one-hit key around it, and is evicted the same way once
 * its turn at probation's tail comes up. A key requested a second time <i>while already in
 * main</i> is promoted to protected, which is evicted from only once probation is exhausted
 * — real, lasting protection that a single-hit key never earns. The sketch and doorkeeper
 * are not idle here (every reference still updates them, and {@link #frequency} exposes the
 * result), but on this cache shape they are inert cargo: nothing yet consults them. That
 * only changes for a weighted cache, where the duel is reachable and the sketch is what
 * decides it. {@link EvictionPolicy}'s documentation points to this policy as LRU's answer
 * to one-hit wonders; on a weighted cache that answer is genuinely the sketch, and on a
 * count-bounded one it is inherited, unmodified, from {@link SlruPolicy}.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class TinyLfuPolicy<K, V> implements EvictionPolicy<K, V> {

    static final int WINDOW = 0;
    static final int PROBATION = 1;
    static final int PROTECTED = 2;

    private final IntrusiveLinkedList<K, V> window = new IntrusiveLinkedList<>();
    private final IntrusiveLinkedList<K, V> probation = new IntrusiveLinkedList<>();
    private final IntrusiveLinkedList<K, V> protectedSegment = new IntrusiveLinkedList<>();

    private final int windowCapacity;
    private final int mainCapacity;
    private final int protectedCapacity;

    private final CountMinSketch sketch;
    private final BloomFilter doorkeeper;
    private final long doorkeeperResetPeriod;
    private long referencesSinceDoorkeeperReset;

    /** A W-TinyLFU policy for a cache of {@code capacity} entries, with the paper's ~1% window. */
    public TinyLfuPolicy(int capacity) {
        this(capacity, 0.01, 0.8);
    }

    /**
     * @param capacity          the cache's entry capacity
     * @param windowFraction    the admission window's share of capacity, in (0, 1]
     * @param protectedFraction main's protected share, in [0, 1) -- see {@link SlruPolicy}
     */
    public TinyLfuPolicy(int capacity, double windowFraction, double protectedFraction) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        if (!(windowFraction > 0 && windowFraction <= 1)) {
            throw new IllegalArgumentException("windowFraction must be in (0, 1], got " + windowFraction);
        }
        if (!(protectedFraction >= 0 && protectedFraction < 1)) {
            throw new IllegalArgumentException("protectedFraction must be in [0, 1), got " + protectedFraction);
        }
        this.windowCapacity = Math.max(1, (int) (capacity * windowFraction));
        this.mainCapacity = Math.max(0, capacity - windowCapacity);
        this.protectedCapacity = (int) (mainCapacity * protectedFraction);
        this.sketch = new CountMinSketch(capacity);
        this.doorkeeper = new BloomFilter(Math.max(1, capacity), 4);
        this.doorkeeperResetPeriod = 10L * Math.max(1, capacity);
    }

    // ------------------------------------------------------------------
    //  Frequency tracking
    // ------------------------------------------------------------------

    /**
     * Records one reference to {@code key} for admission purposes.
     *
     * <p>Called exactly once per distinct reference: from {@link #onAccess} for a hit, and
     * from {@link #beforeInsert} for an arriving key (whether it is a genuine first sighting
     * or a key the cache previously held and is now reloading). {@link #onMiss} deliberately
     * does <b>not</b> also record a use — a cache-aside miss is always followed by exactly
     * this {@link #beforeInsert} call if the caller goes on to store the loaded value, and
     * recording both would double-count the same real-world reference.
     */
    private void recordUse(K key) {
        if (doorkeeper.seenBefore(key)) {
            sketch.increment(key);
        }
        if (++referencesSinceDoorkeeperReset >= doorkeeperResetPeriod) {
            doorkeeper.clear();
            referencesSinceDoorkeeperReset = 0;
        }
    }

    // ------------------------------------------------------------------
    //  EvictionPolicy
    // ------------------------------------------------------------------

    @Override
    public void beforeInsert(K key) {
        recordUse(key);
    }

    @Override
    public void onInsert(Node<K, V> node) {
        // Every arrival, proven or not, starts in the window: see the class Javadoc.
        node.setSegment(WINDOW);
        window.addToHead(node);
    }

    @Override
    public void onAccess(Node<K, V> node) {
        recordUse(node.key());
        switch (node.segment()) {
            case WINDOW -> window.moveToHead(node);
            case PROTECTED -> protectedSegment.moveToHead(node);
            default -> {                              // PROBATION: a second use earns protection
                probation.unlink(node);
                node.setSegment(PROTECTED);
                protectedSegment.addToHead(node);
                if (protectedSegment.size() > protectedCapacity) {
                    Node<K, V> demoted = protectedSegment.removeTail();
                    demoted.setSegment(PROBATION);
                    probation.addToHead(demoted);
                }
            }
        }
    }

    @Override
    public void onRemove(Node<K, V> node) {
        segmentOf(node).unlink(node);
    }

    /**
     * Chooses a victim, resolving the promotion duel along the way (see the class Javadoc).
     *
     * <p>While the window holds more than its target share and main still has spare room,
     * the window's oldest entry is promoted for free -- this changes nothing the cache
     * itself needs to evict, so the search continues. Once the window is over target and
     * main is full, the window's oldest entry duels main's own outgoing entry by estimated
     * frequency; the loser is what this method returns. If the window is at or under its
     * target, the pressure is entirely on main, exactly as it would be for
     * {@link SlruPolicy}.
     *
     * <p>Never returns {@code null} while the cache holds at least one entry: the caller
     * treats a {@code null} victim as "nothing can be evicted, give up on this insert
     * entirely", not as "call me again". A candidate is only ever promoted when there is
     * an actual entry in main to weigh it against; if main cannot hold anything at all
     * ({@code mainCapacity == 0}, only possible for a cache too small to have a meaningful
     * window/main split), the candidate is evicted outright instead.
     */
    @Override
    public Node<K, V> selectVictim() {
        while (window.size() > windowCapacity && probation.size() + protectedSegment.size() < mainCapacity) {
            promoteToProbation(window.tail());
        }
        if (window.size() > windowCapacity) {
            Node<K, V> candidate = window.tail();
            Node<K, V> mainVictim = mainVictim();
            if (mainVictim == null) {
                return candidate;                      // nowhere for the candidate to be promoted to
            }
            if (sketch.estimate(candidate.key()) > sketch.estimate(mainVictim.key())) {
                promoteToProbation(candidate);
                return mainVictim;
            }
            return candidate;                          // the candidate loses the duel outright
        }
        Node<K, V> mainVictim = mainVictim();
        return mainVictim != null ? mainVictim : window.tail();
    }

    private Node<K, V> mainVictim() {
        Node<K, V> fromProbation = probation.tail();
        return fromProbation != null ? fromProbation : protectedSegment.tail();
    }

    private void promoteToProbation(Node<K, V> node) {
        window.unlink(node);
        node.setSegment(PROBATION);
        probation.addToHead(node);
    }

    @Override
    public void clear() {
        window.clear();
        probation.clear();
        protectedSegment.clear();
        sketch.clear();
        doorkeeper.clear();
        referencesSinceDoorkeeperReset = 0;
    }

    @Override
    public String name() {
        return "W-TinyLFU";
    }

    private IntrusiveLinkedList<K, V> segmentOf(Node<K, V> node) {
        return switch (node.segment()) {
            case WINDOW -> window;
            case PROTECTED -> protectedSegment;
            default -> probation;
        };
    }

    /** @return keys in the admission window, most recently used first */
    public java.util.List<K> windowKeys() {
        return window.keysFromMruToLru();
    }

    /** @return keys on main's probation segment, most recently inserted first */
    public java.util.List<K> probationKeys() {
        return probation.keysFromMruToLru();
    }

    /** @return keys on main's protected segment, most recently used first */
    public java.util.List<K> protectedKeys() {
        return protectedSegment.keysFromMruToLru();
    }

    /** @return the sketch's current frequency estimate for {@code key}, for tests and dashboards */
    public int frequency(K key) {
        return sketch.estimate(key);
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        window.assertInvariants();
        probation.assertInvariants();
        protectedSegment.assertInvariants();
        int total = window.size() + probation.size() + protectedSegment.size();
        Invariants.check(total == expectedEntryCount,
                "W-TinyLFU tracks " + total + " entries but the cache holds " + expectedEntryCount);
        Invariants.check(protectedSegment.size() <= protectedCapacity,
                "protected segment holds " + protectedSegment.size() + " entries, above its cap of "
                        + protectedCapacity);
        window.forEach(node -> Invariants.check(node.segment() == WINDOW,
                node.key() + " is on the window list but is not tagged WINDOW"));
        probation.forEach(node -> Invariants.check(node.segment() == PROBATION,
                node.key() + " is on the probation list but is not tagged PROBATION"));
        protectedSegment.forEach(node -> Invariants.check(node.segment() == PROTECTED,
                node.key() + " is on the protected list but is not tagged PROTECTED"));
    }
}
