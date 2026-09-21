package com.velox.core.structure;

import com.velox.core.util.Invariants;

import java.util.ArrayList;
import java.util.List;

/**
 * A doubly linked list of {@link Node}s that tracks <b>recency order</b>.
 *
 * <p>The list runs from most-recently-used (MRU) at the head to
 * least-recently-used (LRU) at the tail:
 *
 * <pre>
 *   head &lt;-&gt; [C] &lt;-&gt; [B] &lt;-&gt; [A] &lt;-&gt; tail
 *             MRU               LRU
 *                                ^
 *                          evict from here
 * </pre>
 *
 * <h2>The contract that makes this O(1)</h2>
 *
 * This list has <b>no search method</b>, and that is deliberate. Searching a
 * linked list is O(n), which would break the cache's constant-time
 * requirement. Instead, the caller must already hold the {@code Node} it
 * wants to operate on — which it always does, because the cache's hash map
 * stores {@code key -> Node} and hands the node over in O(1).
 *
 * <p>So the division of labour is:
 * <ul>
 *   <li>the <b>hash map</b> answers "where is this key?" in O(1)</li>
 *   <li>this <b>list</b> answers "what was used longest ago?" in O(1)</li>
 * </ul>
 * Neither structure can do the job alone. Together they solve the problem.
 *
 * <h2>Thread safety</h2>
 *
 * <b>None.</b> This class is not thread-safe and never will be. Locking is
 * handled a level up, by the shard that owns this list (Tier 2). Keeping
 * synchronisation out of the data structure is what lets us shard the cache
 * later and get real concurrency.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class IntrusiveLinkedList<K, V> {

    /**
     * Permanent dummy node before the first real entry.
     * See {@link Node#Node()} for why sentinels earn their keep.
     */
    private final Node<K, V> head = new Node<>();

    /** Permanent dummy node after the last real entry. */
    private final Node<K, V> tail = new Node<>();

    /** Number of real (non-sentinel) nodes. Kept incrementally so size() is O(1). */
    private int size;

    /** Creates an empty list: the two sentinels pointing at each other. */
    public IntrusiveLinkedList() {
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Inserts {@code node} at the head, marking it most recently used.
     *
     * <p>Four pointer writes, no traversal.
     *
     * <pre>
     *   before:  head &lt;-&gt; X &lt;-&gt; ...
     *   after:   head &lt;-&gt; node &lt;-&gt; X &lt;-&gt; ...
     * </pre>
     *
     * @param node a node that is not currently in any list
     * @implNote O(1)
     */
    public void addToHead(Node<K, V> node) {
        node.prev = head;          // 1. node points back at the sentinel
        node.next = head.next;     // 2. node points forward at the old first entry
        head.next.prev = node;     // 3. the old first entry points back at node
        head.next = node;          // 4. the sentinel points forward at node
        size++;
    }

    /**
     * Removes {@code node} from the list.
     *
     * <p>This is the operation that justifies the whole design. We do not
     * search for the node and we do not shift anything — we simply ask its
     * two neighbours to point at each other:
     *
     * <pre>
     *   before:  A &lt;-&gt; node &lt;-&gt; C
     *   after:   A &lt;-&gt; C          (node is now unreachable)
     * </pre>
     *
     * <p>Step 1 ({@code node.prev.next = ...}) is exactly why this must be a
     * <b>doubly</b> linked list: we need {@code node.prev} to reach the
     * predecessor. In a singly linked list we would have to walk from the
     * head to find it — O(n).
     *
     * @param node a node currently in this list
     * @implNote O(1)
     */
    public void unlink(Node<K, V> node) {
        node.prev.next = node.next;   // predecessor skips over node
        node.next.prev = node.prev;   // successor skips back over node

        // Null the pointers out. Two reasons: it lets the garbage collector
        // reclaim neighbours if this node is held elsewhere, and it turns a
        // "used after removal" bug into an immediate NullPointerException
        // instead of silent corruption.
        node.prev = null;
        node.next = null;
        size--;
    }

    /**
     * Moves an existing node to the head — "this was just used".
     *
     * <p>Called on every cache hit. Remember this: a <i>read</i> of the cache
     * mutates this list. Harmless single-threaded, but it is the root cause
     * of the concurrency problem we solve in Tier 2.
     *
     * @param node a node currently in this list
     * @implNote O(1)
     */
    public void moveToHead(Node<K, V> node) {
        if (head.next == node) {
            return;   // already MRU — skip six pointer writes
        }
        unlink(node);
        addToHead(node);
    }

    /**
     * @return the most recently used node, or {@code null} if empty
     * @implNote O(1)
     */
    public Node<K, V> head() {
        return size == 0 ? null : head.next;
    }

    /**
     * @return the least recently used node — the eviction candidate — or
     *         {@code null} if empty
     * @implNote O(1)
     */
    public Node<K, V> tail() {
        return size == 0 ? null : tail.prev;
    }

    /**
     * Removes and returns the least recently used node.
     *
     * <p>This is eviction. Because the tail sentinel always points straight
     * at the LRU entry, finding the victim costs nothing — no scan, no
     * timestamps to compare.
     *
     * @return the evicted node, or {@code null} if the list was empty
     * @implNote O(1)
     */
    public Node<K, V> removeTail() {
        if (size == 0) {
            return null;
        }
        Node<K, V> victim = tail.prev;
        unlink(victim);
        return victim;
    }

    /** @return the number of entries. @implNote O(1) */
    public int size() {
        return size;
    }

    /** @return whether the list holds no entries. @implNote O(1) */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Empties the list.
     *
     * @implNote O(n) — we walk the list clearing pointers so the garbage
     *           collector can reclaim the nodes even if something still
     *           references one of them.
     */
    public void clear() {
        Node<K, V> current = head.next;
        while (current != tail) {
            Node<K, V> next = current.next;
            current.prev = null;
            current.next = null;
            current = next;
        }
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    /**
     * Visits every node, MRU first.
     *
     * <p>Exists so a policy can check its own invariants ("does every node in
     * this bucket really have this bucket's frequency?") without being given
     * access to the raw pointers.
     *
     * @param action what to do with each node; must not modify the list
     * @implNote O(n) -- invariant checks and tests only
     */
    public void forEach(java.util.function.Consumer<? super Node<K, V>> action) {
        for (Node<K, V> n = head.next; n != tail; n = n.next) {
            action.accept(n);
        }
    }

    /**
     * Returns the keys in order, MRU first. <b>For tests and debugging only.</b>
     *
     * @return a snapshot list of keys from most to least recently used
     * @implNote O(n) — never call this on a hot path
     */
    public List<K> keysFromMruToLru() {
        List<K> keys = new ArrayList<>(size);
        for (Node<K, V> n = head.next; n != tail; n = n.next) {
            keys.add(n.key);
        }
        return keys;
    }

    /**
     * Verifies that this list is not corrupted. Does nothing unless
     * {@code -Dvelox.assertions=true}.
     *
     * <p>Checks, in order:
     * <ol>
     *   <li>the sentinels still sit at the ends</li>
     *   <li>there is no cycle (Floyd's tortoise-and-hare)</li>
     *   <li>walking forwards visits exactly {@code size} nodes</li>
     *   <li>walking backwards visits exactly {@code size} nodes</li>
     *   <li>every {@code prev}/{@code next} pair agrees with each other</li>
     * </ol>
     *
     * @throws IllegalStateException if the list is inconsistent
     */
    public void assertInvariants() {
        if (!Invariants.ENABLED) {
            return;
        }

        Invariants.check(head.prev == null, "head sentinel must have no predecessor");
        Invariants.check(tail.next == null, "tail sentinel must have no successor");
        Invariants.check(size >= 0, "size went negative: " + size);

        // --- 1. Cycle detection FIRST, using Floyd's tortoise-and-hare. ---
        //
        // Two walkers move through the list, one at 1 step and one at 2 steps
        // per iteration. If there is a loop, the fast one laps the slow one
        // and they land on the same node. If there is no loop, the fast one
        // runs off the end and we stop.
        //
        // We do this BEFORE the counting checks on purpose. The counting
        // checks compare against the `size` field — but if `size` is itself
        // wrong, a corrupted list could still pass them. Floyd's algorithm
        // trusts nothing but the pointers, and needs only O(1) extra memory.
        Node<K, V> slow = head;
        Node<K, V> fast = head;
        while (fast != null && fast.next != null) {
            slow = slow.next;
            fast = fast.next.next;
            Invariants.check(slow != fast, "cycle detected — the list loops back on itself");
        }

        // --- 2. Walk forwards: count nodes and verify every back-pointer. ---
        int forwardCount = 0;
        Node<K, V> previous = head;
        for (Node<K, V> n = head.next; n != tail; n = n.next) {
            Invariants.check(n != null, "list ended before reaching the tail sentinel");
            Invariants.check(n.prev == previous,
                    "broken back-pointer at key " + n.key + " (prev.next != node)");
            previous = n;
            forwardCount++;
        }
        Invariants.check(tail.prev == previous, "tail sentinel's back-pointer is wrong");
        Invariants.check(forwardCount == size,
                "forward traversal found " + forwardCount + " nodes but size says " + size);

        // --- 3. Walk backwards: the count must match. ---
        int backwardCount = 0;
        for (Node<K, V> n = tail.prev; n != head; n = n.prev) {
            Invariants.check(n != null, "list ended before reaching the head sentinel");
            backwardCount++;
        }
        Invariants.check(backwardCount == size,
                "backward traversal found " + backwardCount + " nodes but size says " + size);
    }

    @Override
    public String toString() {
        return "MRU " + keysFromMruToLru() + " LRU";
    }
}
