package com.velox.core.policy;

import com.velox.core.structure.Node;
import com.velox.core.util.Invariants;

import java.util.SplittableRandom;

/**
 * Evicts a uniformly random entry.
 *
 * <h2>Why random is a serious baseline, not a joke</h2>
 *
 * It sounds absurd, but Random is famously hard to beat by much, and it wins
 * outright in one situation where the "clever" policies collapse: a loop
 * slightly larger than the cache. Capacity 3, requests {@code 1,2,3,4,1,2,3,4,...}:
 * LRU, FIFO and LFU all score exactly 0%, because they evict on a fixed
 * pattern that happens to be perfectly out of phase with the workload.
 * Random has no pattern to be out of phase with, so it keeps some entries by
 * luck and scores well above zero.
 *
 * <p>It also costs <b>zero metadata</b> and reads are read-only.
 *
 * <h2>The data-structure problem: O(1) random choice AND O(1) removal</h2>
 *
 * Picking a random element needs an array (random access by index). But
 * removing an arbitrary element from the middle of an array is O(n) -- you
 * must shift everything after it. A linked list gives O(1) removal but no
 * random access. We need both.
 *
 * <p>The trick is <b>swap-remove</b>: to delete the element at index i, move
 * the <i>last</i> element into slot i and shrink the array by one.
 *
 * <pre>
 *   remove B from   [A][B][C][D]
 *   move D into B's slot:   [A][D][C][ ]     size 3
 *   order changed, but nobody cares -- we only ever pick at random
 * </pre>
 *
 * Order is irrelevant here, which is what makes the trick legal. For it to be
 * O(1) each node must know its own index ({@link Node#slot()}), so we can jump
 * straight to it instead of searching. That is the same idea as the intrusive
 * list: the entry carries its own position.
 *
 * <h2>Reproducibility</h2>
 *
 * The generator is seeded. Two runs with the same seed and the same requests
 * evict the same entries, which is essential for benchmarking -- an
 * unseeded random policy would give a different hit rate every run and make
 * results impossible to compare or reproduce.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class RandomPolicy<K, V> implements EvictionPolicy<K, V> {

    private static final long DEFAULT_SEED = 0x5EEDL;

    private Node<K, V>[] nodes;
    private int size;
    private final SplittableRandom random;

    /** Creates a policy with a fixed default seed. */
    public RandomPolicy() {
        this(DEFAULT_SEED);
    }

    /** @param seed the random seed; equal seeds reproduce equal eviction sequences */
    @SuppressWarnings("unchecked")
    public RandomPolicy(long seed) {
        this.random = new SplittableRandom(seed);
        this.nodes = (Node<K, V>[]) new Node[16];
    }

    @Override
    public void onInsert(Node<K, V> node) {
        if (size == nodes.length) {
            nodes = java.util.Arrays.copyOf(nodes, size * 2);   // amortised O(1)
        }
        node.setSlot(size);
        nodes[size++] = node;
    }

    @Override
    public void onAccess(Node<K, V> node) {
        // Nothing to update: random eviction ignores usage entirely.
    }

    @Override
    public void onRemove(Node<K, V> node) {
        int hole = node.slot();
        Node<K, V> last = nodes[--size];     // the element that will fill the hole
        nodes[hole] = last;
        last.setSlot(hole);
        nodes[size] = null;                  // do not keep a dead reference alive
        node.setSlot(-1);
    }

    @Override
    public Node<K, V> selectVictim() {
        return size == 0 ? null : nodes[random.nextInt(size)];
    }

    @Override
    public void clear() {
        java.util.Arrays.fill(nodes, 0, size, null);
        size = 0;
    }

    @Override
    public String name() {
        return "RANDOM";
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        Invariants.check(size == expectedEntryCount,
                "RANDOM is tracking " + size + " entries but the cache holds " + expectedEntryCount);

        for (int i = 0; i < size; i++) {
            Invariants.check(nodes[i] != null, "hole in the live region at index " + i);
            // The back-pointer must agree with the array, or swap-remove would
            // write into the wrong slot and silently duplicate or lose entries.
            Invariants.check(nodes[i].slot() == i,
                    "node " + nodes[i].key() + " believes it is at slot " + nodes[i].slot()
                            + " but sits at " + i);
        }
        for (int i = size; i < nodes.length; i++) {
            Invariants.check(nodes[i] == null, "stale reference left beyond size at index " + i);
        }
    }
}
