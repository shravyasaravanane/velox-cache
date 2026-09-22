package com.velox.server.livestats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>Space-Saving</b> algorithm (Metwally, Agrawal &amp; El Abbadi, 2005): tracks the
 * approximate top-{@code k} most frequent keys in an unbounded stream, using memory for exactly
 * {@code k} counters -- never more, no matter how many distinct keys have actually been seen.
 *
 * <h2>The problem it solves</h2>
 *
 * The live dashboard's "hot keys" panel wants the current top few product ids by request
 * frequency, out of a catalog that can run to a million rows. An exact {@code HashMap<Long,
 * Long>} of every key's count would grow with the whole catalog, not with {@code k} -- the same
 * shape of problem {@link com.velox.core.sketch.HyperLogLog} solves for distinct-count and
 * {@link com.velox.core.policy.CountMinSketch} solves for frequency lookups, applied here to
 * "who are the leaders" specifically.
 *
 * <h2>The trick: evict the smallest counter, and let the newcomer inherit its count</h2>
 *
 * While fewer than {@code k} distinct keys have been seen, every new key just gets its own
 * counter, starting at 1. Once all {@code k} counters are in use, a key that has never been
 * seen before does not get a new counter -- it takes over whichever counter currently holds
 * the <b>smallest</b> count, and starts from <i>that count plus one</i>, not from zero.
 *
 * <p>That inherited value is deliberately an overestimate: the evicted key might have had
 * exactly that many hits and the newcomer none yet, so its true count could be as low as 1.
 * The guarantee Space-Saving proves is bounded, not exact: every tracked count is at most
 * {@code trueCount + N/k} above the truth, where {@code N} is the total stream length so far.
 * With {@code k} in the tens and a catalog with genuinely skewed traffic, the handful of truly
 * hot keys accumulate real counts far above that error bound and are never displaced by noise --
 * which is the only property a "hot keys" panel actually needs.
 *
 * <h2>Why a min-heap, not a linear scan</h2>
 *
 * Finding "the smallest counter" and moving a counter up or down after it changes are both the
 * textbook job of a heap: a binary min-heap keeps both operations at {@code O(log k)}, with an
 * auxiliary {@code key -> index} map so {@link #record} can also jump straight to an
 * <i>already-tracked</i> key's counter in {@code O(1)} instead of scanning for it -- essential
 * since {@link #record} runs on every single cache request.
 */
public final class TopKTracker {

    private final int capacity;
    private final long[] keys;
    private final long[] counts;
    private final Map<Long, Integer> indexOf = new HashMap<>();
    private int size;

    public TopKTracker(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
        this.keys = new long[capacity];
        this.counts = new long[capacity];
    }

    /**
     * Records one occurrence of {@code key}.
     *
     * @implNote O(log k): a heap-index lookup, then at most one sift up or down
     */
    public synchronized void record(long key) {
        Integer index = indexOf.get(key);
        if (index != null) {
            counts[index]++;
            siftDown(index); // a larger count sinks away from the min-heap's root
            return;
        }
        if (size < capacity) {
            int i = size++;
            keys[i] = key;
            counts[i] = 1;
            indexOf.put(key, i);
            siftUp(i);
            return;
        }
        // Full: evict the minimum (always at index 0 in a min-heap) and let the newcomer
        // inherit its count, incremented by one -- the Space-Saving overestimate.
        indexOf.remove(keys[0]);
        keys[0] = key;
        counts[0] = counts[0] + 1;
        indexOf.put(key, 0);
        siftDown(0);
    }

    /** @return the currently tracked keys, sorted by count descending -- may hold fewer than
     *          {@code capacity} entries if fewer than that many distinct keys have been seen */
    public synchronized List<Entry> topK() {
        List<Entry> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(new Entry(keys[i], counts[i]));
        }
        result.sort((a, b) -> Long.compare(b.count(), a.count()));
        return result;
    }

    public record Entry(long key, long count) {
    }

    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (counts[parent] <= counts[i]) {
                break;
            }
            swap(i, parent);
            i = parent;
        }
    }

    private void siftDown(int i) {
        while (true) {
            int left = 2 * i + 1;
            int right = 2 * i + 2;
            int smallest = i;
            if (left < size && counts[left] < counts[smallest]) {
                smallest = left;
            }
            if (right < size && counts[right] < counts[smallest]) {
                smallest = right;
            }
            if (smallest == i) {
                break;
            }
            swap(i, smallest);
            i = smallest;
        }
    }

    private void swap(int i, int j) {
        long tempKey = keys[i];
        keys[i] = keys[j];
        keys[j] = tempKey;
        long tempCount = counts[i];
        counts[i] = counts[j];
        counts[j] = tempCount;
        indexOf.put(keys[i], i);
        indexOf.put(keys[j], j);
    }
}
