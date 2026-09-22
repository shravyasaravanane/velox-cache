package com.velox.core.policy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * An indexed binary max-heap over arbitrary keys: the same array/sift technique
 * {@code IndexedMinHeap} uses for expiry deadlines, inverted to a maximum and keyed by a
 * generic {@code K} instead of a {@code Node}.
 *
 * <h2>Why not reuse {@code IndexedMinHeap}</h2>
 *
 * That class is hard-wired to a {@code Node}'s {@code heapIndex} field and its expiry
 * deadline, both specific to the live cache engine. {@link BeladyOracle} runs entirely
 * offline, against a plain list of keys with no {@code Node} in sight, and needs the
 * <i>largest</i> value on top rather than the smallest. Rather than generalise a
 * well-tested, single-purpose class for one more caller, this repeats the technique with
 * its own key (a {@code HashMap<K, Integer>} back-pointer stands in for {@code heapIndex},
 * since there is no node to attach a field to) — consistent with how {@link LruKPolicy}
 * already did the same thing for its own, differently-keyed heap.
 *
 * @param <K> the key type
 */
final class IndexedMaxHeap<K> {

    private Object[] keys;
    private long[] priorities;
    private final Map<K, Integer> indexOf = new HashMap<>();
    private int size;

    IndexedMaxHeap() {
        this.keys = new Object[16];
        this.priorities = new long[16];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    boolean contains(K key) {
        return indexOf.containsKey(key);
    }

    /** @return the key with the largest priority, or {@code null} if empty. O(1) */
    @SuppressWarnings("unchecked")
    K peekMax() {
        return size == 0 ? null : (K) keys[0];
    }

    /** Adds {@code key}, not already present, with the given priority. O(log n) */
    void insert(K key, long priority) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            priorities = Arrays.copyOf(priorities, size * 2);
        }
        keys[size] = key;
        priorities[size] = priority;
        indexOf.put(key, size);
        size++;
        siftUp(size - 1);
    }

    /** Removes and returns the key with the largest priority, or {@code null} if empty. O(log n) */
    K removeMax() {
        if (size == 0) {
            return null;
        }
        K max = peekMax();
        removeAt(0);
        return max;
    }

    /** Repositions {@code key}, already present, after its priority changed to {@code newPriority}. O(log n) */
    void update(K key, long newPriority) {
        Integer i = indexOf.get(key);
        if (i == null) {
            throw new IllegalArgumentException("not present: " + key);
        }
        priorities[i] = newPriority;
        if (!siftUp(i)) {
            siftDown(i);
        }
    }

    @SuppressWarnings("unchecked")
    private void removeAt(int index) {
        int lastIndex = --size;
        Object lastKey = keys[lastIndex];
        long lastPriority = priorities[lastIndex];
        indexOf.remove((K) keys[index]);
        keys[lastIndex] = null;

        if (index != lastIndex) {
            keys[index] = lastKey;
            priorities[index] = lastPriority;
            indexOf.put((K) lastKey, index);
            if (!siftUp(index)) {
                siftDown(index);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean siftUp(int index) {
        Object key = keys[index];
        long priority = priorities[index];
        int i = index;
        while (i > 0) {
            int parentIndex = (i - 1) >>> 1;
            if (priority <= priorities[parentIndex]) {
                break;
            }
            keys[i] = keys[parentIndex];
            priorities[i] = priorities[parentIndex];
            indexOf.put((K) keys[i], i);
            i = parentIndex;
        }
        keys[i] = key;
        priorities[i] = priority;
        indexOf.put((K) key, i);
        return i != index;
    }

    @SuppressWarnings("unchecked")
    private void siftDown(int index) {
        Object key = keys[index];
        long priority = priorities[index];
        int i = index;
        int half = size >>> 1;
        while (i < half) {
            int childIndex = 2 * i + 1;
            long childPriority = priorities[childIndex];
            int rightIndex = childIndex + 1;
            if (rightIndex < size && priorities[rightIndex] > childPriority) {
                childIndex = rightIndex;
                childPriority = priorities[rightIndex];
            }
            if (childPriority <= priority) {
                break;
            }
            keys[i] = keys[childIndex];
            priorities[i] = childPriority;
            indexOf.put((K) keys[i], i);
            i = childIndex;
        }
        keys[i] = key;
        priorities[i] = priority;
        indexOf.put((K) key, i);
    }
}
