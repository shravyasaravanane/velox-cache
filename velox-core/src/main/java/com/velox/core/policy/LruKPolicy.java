package com.velox.core.policy;

import com.velox.core.structure.Node;
import com.velox.core.util.Invariants;

import java.util.Arrays;

/**
 * <b>LRU-K</b> (O'Neil, O'Neil &amp; Weikum, 1993): evict by the <i>K-th</i> most recent
 * reference, not the most recent one.
 *
 * <h2>The idea</h2>
 *
 * Plain LRU asks one question: "when was this last used?" That treats a key touched once,
 * a moment ago, as equally safe as a key touched fifty times over the last hour — which is
 * exactly the opening a single large scan exploits (see {@link LruPolicy}'s and
 * {@link SlruPolicy}'s documentation). LRU-K asks a stronger question: "when was this used
 * for the <b>K-th</b> most recent time?" A key needs {@code K} references, not one, before
 * it is judged by how recently it was used — and until it has that many, it is treated as
 * the <i>most</i> evictable thing in the cache, worse than even a key that was genuinely
 * popular a long time ago and has since gone quiet.
 *
 * <h2>Backward K-distance</h2>
 *
 * The formal quantity is the <b>backward K-distance</b>: {@code now - timeOfKthMostRecentReference}.
 * The victim is whichever entry has the <b>largest</b> backward K-distance — its K-th
 * reference is furthest in the past, or it has never been referenced K times at all, which
 * counts as an infinite distance (see {@link History}).
 *
 * <p>Maximising {@code now - time} is the same as minimising {@code time} (the same
 * {@code now} applies to every entry being compared), so the victim is simply the entry
 * with the <b>smallest</b> recorded K-th-most-recent time — which is exactly what a
 * min-heap gives in O(1), the same trick {@code IndexedMinHeap} uses for "earliest
 * deadline". This class does not reuse that heap (it is hard-wired to expiry deadlines);
 * it repeats the same array-based indexed-heap technique with its own key.
 *
 * <h2>Costs</h2>
 *
 * {@code onAccess} and {@code onInsert} update a history of the last {@code K} references
 * (O(K), a small fixed constant) and then reposition the entry in the heap (O(log n)).
 * This is the one policy in the project where an operation is not O(1) — the eviction
 * SPI's contract documents it as the sole exception.
 *
 * <p>Reuses {@link Node#slot()} as the heap's index back-pointer (free scratch space: no
 * other policy uses it) and {@link Node#policyData()} to hold each entry's {@link History}.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class LruKPolicy<K, V> implements EvictionPolicy<K, V> {

    private final int k;
    private Node<K, V>[] heap;
    private int size;

    /** A logical clock, ticked once per reference. Deterministic, and never wraps in practice. */
    private long clock;

    /**
     * Entries that have not yet been referenced {@code k} times are ranked below every
     * entry that has, however long ago that entry's k-th reference was. This offset is what
     * makes that true using ordinary {@code long} comparison: subtracting it from an
     * under-referenced entry's key pushes it below the smallest key any fully-referenced
     * entry could ever have, and it is astronomically larger than any clock value this
     * policy could reach (the clock would need to tick roughly 4.6 * 10^18 times).
     */
    private static final long UNPROVEN_OFFSET = Long.MAX_VALUE / 2;

    /** An LRU-K policy that evicts by the 2nd most recent reference. */
    public LruKPolicy() {
        this(2);
    }

    /** @param k how many references an entry needs before it is judged by recency; at least 1 */
    @SuppressWarnings("unchecked")
    public LruKPolicy(int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1, got " + k);
        }
        this.k = k;
        this.heap = (Node<K, V>[]) new Node[16];
    }

    // ------------------------------------------------------------------
    //  Per-entry history
    // ------------------------------------------------------------------

    /**
     * The last (up to) {@code k} reference times for one entry, oldest at index 0.
     *
     * <p>{@code k} is small (2, in the common case), so a plain shift-on-write array is
     * simpler than a circular buffer and just as fast in practice.
     */
    private static final class History {
        private final long[] times;
        private int count;

        History(int k) {
            this.times = new long[k];
        }

        void record(long time) {
            if (count < times.length) {
                times[count++] = time;
            } else {
                System.arraycopy(times, 1, times, 0, times.length - 1);
                times[times.length - 1] = time;
            }
        }

        boolean isFull() {
            return count == times.length;
        }

        /** The k-th most recent reference once full; the very first reference otherwise. */
        long oldestRemembered() {
            return times[0];
        }
    }

    private long sortKey(Node<K, V> node) {
        History history = (History) node.policyData();
        return history.isFull() ? history.oldestRemembered() : history.oldestRemembered() - UNPROVEN_OFFSET;
    }

    // ------------------------------------------------------------------
    //  EvictionPolicy
    // ------------------------------------------------------------------

    @Override
    public void onInsert(Node<K, V> node) {
        History history = new History(k);
        history.record(++clock);
        node.setPolicyData(history);
        insert(node);
    }

    @Override
    public void onAccess(Node<K, V> node) {
        History history = (History) node.policyData();
        history.record(++clock);
        update(node);
    }

    @Override
    public void onRemove(Node<K, V> node) {
        removeAt(node.slot());
        node.setPolicyData(null);
    }

    @Override
    public Node<K, V> selectVictim() {
        return size == 0 ? null : heap[0];
    }

    @Override
    public void clear() {
        for (int i = 0; i < size; i++) {
            heap[i].setSlot(-1);
            heap[i].setPolicyData(null);
            heap[i] = null;
        }
        size = 0;
    }

    @Override
    public String name() {
        return "LRU-" + k;
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        Invariants.check(size == expectedEntryCount,
                "LRU-" + k + " tracks " + size + " entries but the cache holds " + expectedEntryCount);
        for (int i = 0; i < size; i++) {
            Node<K, V> node = heap[i];
            Invariants.check(node != null, "null node inside the live region at index " + i);
            Invariants.check(node.slot() == i,
                    "node " + node.key() + " believes it is at index " + node.slot() + " but sits at " + i);
            Invariants.check(node.policyData() instanceof History,
                    "node " + node.key() + " is in the heap without a reference history");
            if (i > 0) {
                Node<K, V> parent = heap[(i - 1) >>> 1];
                Invariants.check(sortKey(node) >= sortKey(parent),
                        "heap property broken: node at " + i + " sorts before its parent");
            }
        }
        for (int i = size; i < heap.length; i++) {
            Invariants.check(heap[i] == null, "stale reference left beyond size at index " + i);
        }
    }

    // ------------------------------------------------------------------
    //  The indexed min-heap, keyed by sortKey(node)
    // ------------------------------------------------------------------

    private void insert(Node<K, V> node) {
        if (size == heap.length) {
            heap = Arrays.copyOf(heap, size * 2);
        }
        heap[size] = node;
        node.setSlot(size);
        size++;
        siftUp(node.slot());
    }

    private void update(Node<K, V> node) {
        int i = node.slot();
        if (!siftUp(i)) {
            siftDown(i);
        }
    }

    private void removeAt(int index) {
        Node<K, V> removed = heap[index];
        int lastIndex = --size;
        Node<K, V> last = heap[lastIndex];
        heap[lastIndex] = null;

        if (index != lastIndex) {
            heap[index] = last;
            last.setSlot(index);
            if (!siftUp(index)) {
                siftDown(index);
            }
        }
        removed.setSlot(-1);
    }

    private boolean siftUp(int index) {
        Node<K, V> node = heap[index];
        long key = sortKey(node);
        int i = index;
        while (i > 0) {
            int parentIndex = (i - 1) >>> 1;
            Node<K, V> parent = heap[parentIndex];
            if (key >= sortKey(parent)) {
                break;
            }
            heap[i] = parent;
            parent.setSlot(i);
            i = parentIndex;
        }
        heap[i] = node;
        node.setSlot(i);
        return i != index;
    }

    private void siftDown(int index) {
        Node<K, V> node = heap[index];
        long key = sortKey(node);
        int i = index;
        int half = size >>> 1;
        while (i < half) {
            int childIndex = 2 * i + 1;
            Node<K, V> child = heap[childIndex];
            long childKey = sortKey(child);
            int rightIndex = childIndex + 1;
            if (rightIndex < size) {
                long rightKey = sortKey(heap[rightIndex]);
                if (rightKey < childKey) {
                    childIndex = rightIndex;
                    child = heap[childIndex];
                    childKey = rightKey;
                }
            }
            if (childKey >= key) {
                break;
            }
            heap[i] = child;
            child.setSlot(i);
            i = childIndex;
        }
        heap[i] = node;
        node.setSlot(i);
    }
}
