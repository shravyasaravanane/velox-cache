package com.velox.core.structure;

import com.velox.core.util.Invariants;

import java.util.Arrays;

/**
 * A binary min-heap of {@link Node}s ordered by expiry deadline, where every
 * node knows its own position in the heap.
 *
 * <h2>What a heap gives us</h2>
 *
 * The question expiry keeps asking is "which entry expires next?" A sorted
 * structure answers it, but keeping everything sorted is expensive. A
 * <b>binary heap</b> keeps only a weak ordering -- every parent is no later
 * than its children -- and that is enough to make the minimum instantly
 * available at the root:
 *
 * <pre>
 *                  [ t=3 ]                 array:  [3][7][5][9][8][6]
 *                  /     \                 index:   0  1  2  3  4  5
 *             [ t=7 ]   [ t=5 ]
 *             /    \      /
 *        [ t=9 ] [ t=8 ] [ t=6 ]           parent(i)  = (i - 1) / 2
 *                                          children   = 2i + 1, 2i + 2
 * </pre>
 *
 * It lives in a plain array: no node objects, no pointers, and the parent/child
 * relationship is pure arithmetic. That is why heaps are fast in practice --
 * neighbouring elements sit next to each other in memory.
 *
 * <h2>Why "indexed"? Because plain heaps cannot cancel</h2>
 *
 * A textbook heap supports insert and remove-the-minimum, and nothing else.
 * A cache needs more:
 *
 * <ul>
 *   <li>an entry is <b>evicted</b> or invalidated before its deadline, so it
 *       must be removed from the <i>middle</i> of the heap;</li>
 *   <li>with expire-after-access, every <b>read</b> pushes the deadline
 *       forward, so an arbitrary entry must be repositioned.</li>
 * </ul>
 *
 * Both start with the same problem: <i>where is this node in the array?</i> In
 * a plain heap the only way to find out is to scan -- O(n) -- on what could be
 * every cache hit.
 *
 * <p>So each {@link Node} stores its own position in {@link Node#heapIndex}, and
 * every swap in this class updates it. Finding a node becomes a field read
 * (O(1)) and repairing the heap around it is O(log n). It is the same idea as
 * the intrusive linked list: <b>the entry carries its own address</b>.
 *
 * <h2>Complexity</h2>
 *
 * <ul>
 *   <li>{@link #peek()}: O(1)</li>
 *   <li>{@link #insert}, {@link #poll}, {@link #remove}, {@link #update}: O(log n)</li>
 *   <li>{@link #contains}: O(1) -- thanks to the index</li>
 * </ul>
 *
 * <h2>Wrap-around-safe ordering</h2>
 *
 * Deadlines are {@code System.nanoTime()} values, whose origin is arbitrary and
 * which can wrap past {@code Long.MAX_VALUE}. Comparing two such values with
 * {@code <} gives the wrong answer across a wrap; comparing their
 * <i>difference</i> to zero stays right. Every comparison here is
 * {@code a - b < 0}.
 *
 * <h2>Thread safety</h2>
 *
 * <b>None.</b> The owning shard holds the lock.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class IndexedMinHeap<K, V> {

    private static final int INITIAL_CAPACITY = 16;

    private Node<K, V>[] heap;
    private int size;

    /** Creates an empty heap. */
    @SuppressWarnings("unchecked")
    public IndexedMinHeap() {
        this.heap = (Node<K, V>[]) new Node[INITIAL_CAPACITY];
    }

    // ------------------------------------------------------------------
    //  Queries
    // ------------------------------------------------------------------

    /** @return the node with the earliest deadline, or {@code null}. @implNote O(1) */
    public Node<K, V> peek() {
        return size == 0 ? null : heap[0];
    }

    /** @return the number of nodes. @implNote O(1) */
    public int size() {
        return size;
    }

    /** @return whether the heap is empty. @implNote O(1) */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * @param node any node
     * @return whether {@code node} is currently in this heap
     * @implNote O(1). Checks that the node's recorded index really points back
     *           at it, so a stale index from some other structure cannot fool it.
     */
    public boolean contains(Node<K, V> node) {
        int i = node.heapIndex;
        return i >= 0 && i < size && heap[i] == node;
    }

    // ------------------------------------------------------------------
    //  Mutation
    // ------------------------------------------------------------------

    /**
     * Adds {@code node}, ordered by its current {@link Node#expiresAtNanos()}.
     *
     * @param node a node with a deadline that is not already in the heap
     * @implNote O(log n)
     */
    public void insert(Node<K, V> node) {
        if (size == heap.length) {
            heap = Arrays.copyOf(heap, size * 2);     // amortised O(1)
        }
        heap[size] = node;
        node.heapIndex = size;
        size++;
        siftUp(node.heapIndex);
    }

    /**
     * Removes and returns the node with the earliest deadline.
     *
     * @return the earliest node, or {@code null} if empty
     * @implNote O(log n)
     */
    public Node<K, V> poll() {
        if (size == 0) {
            return null;
        }
        Node<K, V> root = heap[0];
        removeAt(0);
        return root;
    }

    /**
     * Removes an arbitrary node -- the operation a plain heap cannot do fast.
     *
     * @param node the node to remove
     * @return whether it was in the heap
     * @implNote O(log n)
     */
    public boolean remove(Node<K, V> node) {
        if (!contains(node)) {
            return false;
        }
        removeAt(node.heapIndex);
        return true;
    }

    /**
     * Repositions {@code node} after its deadline changed.
     *
     * <p>The caller sets the new deadline on the node first, then calls this.
     * We cannot know in advance whether the node should move up (deadline got
     * earlier) or down (deadline got later), so we try both; at most one moves.
     *
     * @param node a node currently in the heap
     * @implNote O(log n)
     */
    public void update(Node<K, V> node) {
        int i = node.heapIndex;
        if (!siftUp(i)) {
            siftDown(i);
        }
    }

    /** Removes every node. @implNote O(n) so stale indexes cannot survive. */
    public void clear() {
        for (int i = 0; i < size; i++) {
            heap[i].heapIndex = -1;
            heap[i] = null;
        }
        size = 0;
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * Deletes the node at {@code index}: move the LAST element into the hole,
     * then repair whichever direction it now violates.
     *
     * <p>Filling the hole with the last element keeps the array dense (no
     * gaps), but that element came from a different branch of the tree and may
     * be smaller than the hole's parent or larger than its new children, so it
     * can need to move either way.
     */
    private void removeAt(int index) {
        Node<K, V> removed = heap[index];
        int lastIndex = --size;
        Node<K, V> last = heap[lastIndex];
        heap[lastIndex] = null;

        if (index != lastIndex) {
            heap[index] = last;
            last.heapIndex = index;
            if (!siftUp(index)) {
                siftDown(index);
            }
        }
        removed.heapIndex = -1;
    }

    /**
     * Moves the node at {@code index} towards the root while it is earlier
     * than its parent.
     *
     * @return whether it moved at all
     */
    private boolean siftUp(int index) {
        Node<K, V> node = heap[index];
        int i = index;
        while (i > 0) {
            int parentIndex = (i - 1) >>> 1;
            Node<K, V> parent = heap[parentIndex];
            if (!earlier(node, parent)) {
                break;
            }
            heap[i] = parent;                 // pull the parent down
            parent.heapIndex = i;
            i = parentIndex;
        }
        heap[i] = node;
        node.heapIndex = i;
        return i != index;
    }

    /** Moves the node at {@code index} towards the leaves while a child is earlier. */
    private void siftDown(int index) {
        Node<K, V> node = heap[index];
        int i = index;
        int half = size >>> 1;               // indexes at or past this have no children
        while (i < half) {
            int childIndex = 2 * i + 1;
            Node<K, V> child = heap[childIndex];
            int rightIndex = childIndex + 1;
            if (rightIndex < size && earlier(heap[rightIndex], child)) {
                childIndex = rightIndex;      // follow the earlier of the two children
                child = heap[childIndex];
            }
            if (!earlier(child, node)) {
                break;
            }
            heap[i] = child;                  // pull the child up
            child.heapIndex = i;
            i = childIndex;
        }
        heap[i] = node;
        node.heapIndex = i;
    }

    /** Whether {@code a} expires strictly before {@code b}; safe across nanoTime wrap-around. */
    private static boolean earlier(Node<?, ?> a, Node<?, ?> b) {
        return a.expiresAtNanos - b.expiresAtNanos < 0;
    }

    // ------------------------------------------------------------------
    //  Self-check
    // ------------------------------------------------------------------

    /**
     * Verifies the heap is consistent. Does nothing unless
     * {@code -Dvelox.assertions=true}.
     *
     * <p>Checks the <b>heap property</b> (no child is earlier than its parent),
     * and the <b>index property</b> (every node's {@code heapIndex} equals its
     * real array position). The second is the easy one to break: a swap that
     * moves a node but forgets to update its index leaves the heap looking
     * sorted while every later {@link #remove} or {@link #update} operates on
     * the wrong element.
     *
     * @throws IllegalStateException if the heap is inconsistent
     */
    public void assertInvariants() {
        if (!Invariants.ENABLED) {
            return;
        }
        Invariants.check(size >= 0 && size <= heap.length, "size " + size + " is out of range");

        for (int i = 0; i < size; i++) {
            Node<K, V> node = heap[i];
            Invariants.check(node != null, "null node inside the live region at index " + i);
            Invariants.check(node.heapIndex == i,
                    "node " + node.key + " believes it is at index " + node.heapIndex + " but sits at " + i);
            Invariants.check(node.hasDeadline, "node " + node.key + " is in the heap without a deadline");

            if (i > 0) {
                Node<K, V> parent = heap[(i - 1) >>> 1];
                Invariants.check(!earlier(node, parent),
                        "heap property broken: node at " + i + " expires before its parent");
            }
        }
        for (int i = size; i < heap.length; i++) {
            Invariants.check(heap[i] == null, "stale reference left beyond size at index " + i);
        }
    }
}
