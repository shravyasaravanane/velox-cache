package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.util.Invariants;

/**
 * Least Frequently Used, in true O(1), with optional frequency aging.
 *
 * <h2>The idea, and where LRU goes wrong</h2>
 *
 * LRU only knows <i>when</i> an entry was last touched. A key requested a
 * thousand times today loses to one requested once five seconds ago. LFU
 * fixes that by counting uses and evicting the entry with the <b>fewest</b>.
 *
 * <h2>The hard part: doing it in O(1)</h2>
 *
 * Finding "the entry with the smallest count" sounds like a heap job, and a
 * heap gives O(log n) per operation -- which breaks our constant-time rule.
 * The O(1) construction (Shah, Mitra and Matani, 2010) is a <b>list of
 * lists</b>:
 *
 * <pre>
 *   sentinel -&gt; [freq 1] -&gt; [freq 2] -&gt; [freq 5] -&gt; [freq 99]
 *                  |            |            |            |
 *                A B C         D E           F          G H I J
 *                ^ each bucket is an LRU list; its tail is the tie-break victim
 * </pre>
 *
 * The outer chain is sorted by frequency and only has buckets that are
 * non-empty. The key observation is what happens on a hit:
 *
 * <ul>
 *   <li>an entry at frequency <i>f</i> moves to frequency <i>f + 1</i>;</li>
 *   <li>the bucket for <i>f + 1</i>, if it exists, is <b>the very next one in
 *       the chain</b> -- because the chain is sorted and there is nothing
 *       between <i>f</i> and <i>f + 1</i>;</li>
 *   <li>if it does not exist, it belongs immediately after the current bucket,
 *       so creating it is O(1) too.</li>
 * </ul>
 *
 * No searching, ever. The victim is the tail of the <b>first</b> bucket: the
 * lowest frequency, and among ties the least recently used. Every operation
 * is a handful of pointer writes.
 *
 * <h2>The tie-break matters</h2>
 *
 * Many entries share the lowest count (often 1). Evicting an arbitrary one
 * would be legal but bad; evicting the <i>least recently used</i> of them
 * keeps the entry that has at least been touched lately. That is why each
 * bucket is itself an LRU list rather than a set.
 *
 * <h2>LFU's own failure mode: it never forgets</h2>
 *
 * A key that was hot last Tuesday keeps its huge count forever and blocks
 * today's hot keys from staying in the cache -- "cache pollution". New
 * entries start at frequency 1 and are the first to be evicted, so they never
 * get the chance to build a count.
 *
 * <p>The fix is <b>aging</b>: every {@code agingPeriod} requests, halve every
 * count (never below 1). Old popularity decays geometrically while relative
 * order is preserved, so a formerly hot key can be overtaken. Whether aging
 * helps is a measurable question -- see the hot-set-shift test.
 *
 * <p>Aging is counted on <b>hits and misses</b>, not just hits. That choice
 * is deliberate: the situation aging exists to repair is a stale hot set
 * blocking a new one, and in that situation the new keys are missing. Counting
 * only hits would mean aging never fires precisely when it is needed.
 *
 * <h2>Complexity</h2>
 *
 * Every operation is O(1) except aging, which is O(n) once per
 * {@code agingPeriod} requests -- amortised O(n / agingPeriod) per request,
 * so with the usual period of 10x the capacity that is O(1/10) per request.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class LfuPolicy<K, V> implements EvictionPolicy<K, V> {

    /** All entries that currently share one use count. */
    private static final class Bucket<K, V> {
        final int frequency;
        final IntrusiveLinkedList<K, V> entries = new IntrusiveLinkedList<>();
        Bucket<K, V> prev;
        Bucket<K, V> next;

        Bucket(int frequency) {
            this.frequency = frequency;
        }
    }

    /**
     * Frequency-0 dummy at the front of the bucket chain, so inserting the
     * first real bucket needs no special case (same trick as list sentinels).
     */
    private final Bucket<K, V> sentinel = new Bucket<>(0);

    /** Halve all counts every this many requests; 0 disables aging. */
    private final long agingPeriod;

    private long requestsSinceAging;
    private int size;

    /** Creates an LFU with aging disabled. */
    public LfuPolicy() {
        this(0);
    }

    /**
     * @param agingPeriod halve all frequencies every this many requests
     *                    (hits plus misses); 0 disables aging. A good value
     *                    is around ten times the cache capacity.
     */
    public LfuPolicy(long agingPeriod) {
        if (agingPeriod < 0) {
            throw new IllegalArgumentException("agingPeriod must be >= 0, got " + agingPeriod);
        }
        this.agingPeriod = agingPeriod;
    }

    // ------------------------------------------------------------------
    //  EvictionPolicy
    // ------------------------------------------------------------------

    @Override
    public void onInsert(Node<K, V> node) {
        Bucket<K, V> first = sentinel.next;
        Bucket<K, V> bucket = (first != null && first.frequency == 1)
                ? first
                : insertBucketAfter(sentinel, 1);

        node.setFrequency(1);
        bucket.entries.addToHead(node);
        node.setPolicyData(bucket);
        size++;
    }

    @Override
    public void onAccess(Node<K, V> node) {
        Bucket<K, V> current = bucketOf(node);
        int frequency = current.frequency;

        if (frequency == Integer.MAX_VALUE) {
            // Saturated: stop counting rather than overflow to a negative
            // number, which would make the hottest entry the next victim.
            current.entries.moveToHead(node);
            countRequest();
            return;
        }

        // The bucket for frequency+1 is either the very next one in the chain
        // or does not exist and belongs directly after this one. No search.
        Bucket<K, V> target = current.next;
        if (target == null || target.frequency != frequency + 1) {
            target = insertBucketAfter(current, frequency + 1);
        }

        current.entries.unlink(node);
        if (current.entries.isEmpty()) {
            removeBucket(current);        // keep the chain free of empty buckets
        }

        target.entries.addToHead(node);   // head = most recently used in its bucket
        node.setFrequency(frequency + 1);
        node.setPolicyData(target);

        countRequest();
    }

    @Override
    public void onMiss(K key) {
        countRequest();
    }

    @Override
    public void onRemove(Node<K, V> node) {
        Bucket<K, V> bucket = bucketOf(node);
        bucket.entries.unlink(node);
        if (bucket.entries.isEmpty()) {
            removeBucket(bucket);
        }
        node.setPolicyData(null);
        node.setFrequency(0);
        size--;
    }

    @Override
    public Node<K, V> selectVictim() {
        Bucket<K, V> lowest = sentinel.next;
        return lowest == null ? null : lowest.entries.tail();   // LRU among the least frequent
    }

    @Override
    public void clear() {
        sentinel.next = null;
        size = 0;
        requestsSinceAging = 0;
    }

    @Override
    public String name() {
        return agingPeriod > 0 ? "LFU-AGED" : "LFU";
    }

    // ------------------------------------------------------------------
    //  Aging
    // ------------------------------------------------------------------

    private void countRequest() {
        if (agingPeriod > 0 && ++requestsSinceAging >= agingPeriod) {
            requestsSinceAging = 0;
            age();
        }
    }

    /**
     * Halves every entry's frequency (minimum 1) and rebuilds the bucket chain.
     *
     * <p>Halving is monotonic -- if a &lt;= b then a/2 &lt;= b/2 -- so the
     * relative order of buckets is preserved and some neighbouring buckets
     * simply <i>merge</i> (frequencies 4 and 5 both become 2). We can
     * therefore walk the old chain once, in ascending order, appending to a
     * new chain.
     *
     * <p>Within a merged bucket we want the entries from the <i>lower</i> old
     * frequency to be evicted first. Draining each old bucket from its tail
     * (LRU first) and pushing each entry onto the head of the new bucket puts
     * the first-drained entries deepest, i.e. nearest the tail. That yields
     * exactly the order (old frequency, then recency) with no sorting.
     *
     * @implNote O(n) in the number of entries.
     */
    private void age() {
        Bucket<K, V> oldChain = sentinel.next;
        sentinel.next = null;                 // detach; we rebuild from scratch
        Bucket<K, V> newestBucket = sentinel;

        for (Bucket<K, V> old = oldChain; old != null; old = old.next) {
            int halved = Math.max(1, old.frequency >> 1);

            if (newestBucket.frequency != halved) {
                newestBucket = insertBucketAfter(newestBucket, halved);
            }

            Node<K, V> node;
            while ((node = old.entries.removeTail()) != null) {
                node.setFrequency(halved);
                node.setPolicyData(newestBucket);
                newestBucket.entries.addToHead(node);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Bucket chain plumbing
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Bucket<K, V> bucketOf(Node<K, V> node) {
        return (Bucket<K, V>) node.policyData();
    }

    private Bucket<K, V> insertBucketAfter(Bucket<K, V> position, int frequency) {
        Bucket<K, V> fresh = new Bucket<>(frequency);
        fresh.prev = position;
        fresh.next = position.next;
        if (position.next != null) {
            position.next.prev = fresh;
        }
        position.next = fresh;
        return fresh;
    }

    private void removeBucket(Bucket<K, V> bucket) {
        bucket.prev.next = bucket.next;
        if (bucket.next != null) {
            bucket.next.prev = bucket.prev;
        }
        bucket.prev = null;
        bucket.next = null;
    }

    // ------------------------------------------------------------------
    //  Self-check
    // ------------------------------------------------------------------

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }

        int counted = 0;
        int previousFrequency = 0;
        Bucket<K, V> previousBucket = sentinel;
        int guard = 0;

        for (Bucket<K, V> bucket = sentinel.next; bucket != null; bucket = bucket.next) {
            Invariants.check(++guard <= size + 1, "bucket chain is longer than the entry count -- cycle?");
            Invariants.check(bucket.prev == previousBucket,
                    "bucket " + bucket.frequency + " has a broken back-pointer");
            Invariants.check(bucket.frequency > previousFrequency,
                    "buckets are not strictly ascending: " + previousFrequency + " then " + bucket.frequency);
            Invariants.check(!bucket.entries.isEmpty(),
                    "empty bucket " + bucket.frequency + " left in the chain");

            bucket.entries.assertInvariants();
            final Bucket<K, V> owner = bucket;
            bucket.entries.forEach(node -> {
                Invariants.check(node.frequency() == owner.frequency,
                        "node " + node.key() + " has frequency " + node.frequency()
                                + " but sits in bucket " + owner.frequency);
                Invariants.check(node.policyData() == owner,
                        "node " + node.key() + " points at a different bucket than the one holding it");
            });

            counted += bucket.entries.size();
            previousFrequency = bucket.frequency;
            previousBucket = bucket;
        }

        Invariants.check(counted == size, "buckets hold " + counted + " entries but size says " + size);
        Invariants.check(size == expectedEntryCount,
                "LFU is tracking " + size + " entries but the cache holds " + expectedEntryCount);
    }
}
