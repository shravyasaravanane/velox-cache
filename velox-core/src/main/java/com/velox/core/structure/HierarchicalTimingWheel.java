package com.velox.core.structure;

import com.velox.core.util.Invariants;

import java.util.ArrayList;
import java.util.List;

/**
 * A hierarchical timing wheel: O(1) scheduling and cancellation of timeouts.
 *
 * <h2>The idea: index by time instead of sorting by time</h2>
 *
 * A heap keeps timeouts <i>sorted</i>, and sorting costs O(log n). A timing
 * wheel skips sorting entirely by using the deadline as an <i>array index</i>.
 * Picture a clock face of {@code wheelSize} slots, each covering one tick:
 *
 * <pre>
 *            slot 0
 *        7 /   |   \ 1          A timeout 3 ticks from now goes in the slot
 *      6 --  [hand] -- 2        3 positions ahead of the hand. One array
 *        5 \   |   / 3          write. Cancelling unlinks it from its slot's
 *            slot 4             list. Neither involves comparing deadlines.
 * </pre>
 *
 * As time advances the hand sweeps forward, and everything in the slots it
 * passes has expired.
 *
 * <h2>Why hierarchical</h2>
 *
 * One wheel only reaches {@code wheelSize} ticks ahead. For longer timeouts we
 * stack wheels, where each level's slot is as wide as the <i>entire wheel below
 * it</i>:
 *
 * <pre>
 *   level 0:  64 slots x 1 tick        covers 64 ticks
 *   level 1:  64 slots x 64 ticks      covers 4,096 ticks
 *   level 2:  64 slots x 4,096 ticks   covers 262,144 ticks
 * </pre>
 *
 * A distant timeout sits in a coarse slot. When the hand reaches that slot it
 * is <b>cascaded</b>: every timeout in it is re-filed into the finer level
 * below, where it now falls within reach. Each timeout cascades at most once
 * per level, so the amortised cost per timeout stays O(1). (This is the same
 * arithmetic as a clock: the hour hand only matters until the minute hand can
 * take over.)
 *
 * <h2>This wheel is exact</h2>
 *
 * Production wheels (Kafka, Netty) fire on tick boundaries, so an entry may be
 * reported up to a tick early or late. <b>Early is unacceptable here</b>: the
 * cache would delete an entry that is still live. So the bucket for the
 * <i>current</i> tick is scanned against the exact clock. Nothing is ever
 * reported before its deadline, and nothing is reported late.
 *
 * <p>The cost of that guarantee is that each poll scans the current tick's
 * bucket -- the timeouts landing inside one tick -- rather than doing nothing.
 *
 * <h2>Time is counted relative to a start instant</h2>
 *
 * All slot arithmetic uses ticks <i>since the wheel was created</i>, computed
 * by subtracting a recorded start time. Absolute {@code System.nanoTime()}
 * values have an arbitrary origin and may wrap around, and deriving slot
 * indexes from them would break at the wrap; a subtraction stays correct.
 *
 * <h2>Skipping empty time</h2>
 *
 * Stepping the hand one tick at a time would make a cache that sat idle for a
 * month spin billions of times. Instead, when the finest levels are empty the
 * hand jumps straight to the next instant at which anything could happen (the
 * next cascade boundary). Jumping ten years costs a few dozen steps.
 *
 * <h2>Complexity</h2>
 *
 * <ul>
 *   <li>{@link #schedule}: O(levels), and levels is a small constant (about 7
 *       for 1 ms ticks reaching centuries) -- effectively O(1)</li>
 *   <li>{@link #cancel}: O(1)</li>
 *   <li>{@link #pollExpired}: amortised O(1) plus a scan of the current bucket</li>
 * </ul>
 *
 * <h2>Ownership</h2>
 *
 * A node may be tracked by <b>at most one wheel</b> at a time. It records the
 * bucket it sits in and trusts that pointer, so scheduling a node that is still
 * tracked by a <i>different</i> wheel would unlink it from the wrong structure
 * and corrupt both. In the cache this cannot happen -- a node lives in one
 * cache for its whole life -- but anything that builds several wheels over the
 * same nodes must {@link #cancel} them first or use fresh nodes. (The indexed
 * heap is more forgiving here: its {@code contains} check verifies identity.)
 *
 * <h2>Thread safety</h2>
 *
 * <b>None.</b> The owner holds the lock.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class HierarchicalTimingWheel<K, V> {

    /** Deadlines are clamped below 2^62 ticks so level widths can never overflow a long. */
    private static final long MAX_TICK = 1L << 62;

    /** One slot: a doubly linked list of the nodes filed under it. */
    private static final class Bucket<K, V> {
        final Level<K, V> owner;   // null for the "ready" list, which is not part of the wheel
        Node<K, V> head;
        int count;

        Bucket(Level<K, V> owner) {
            this.owner = owner;
        }
    }

    /** One ring of slots. */
    private static final class Level<K, V> {
        final long width;                 // how many ticks each slot spans
        final Bucket<K, V>[] buckets;
        int count;                        // nodes currently filed at this level

        @SuppressWarnings("unchecked")
        Level(long width, int wheelSize) {
            this.width = width;
            this.buckets = (Bucket<K, V>[]) new Bucket[wheelSize];
            for (int i = 0; i < wheelSize; i++) {
                buckets[i] = new Bucket<>(this);
            }
        }
    }

    private final long tickNanos;
    private final int wheelSize;
    private final int mask;
    private final long startNanos;

    private final List<Level<K, V>> levels = new ArrayList<>();

    /** Nodes whose deadline has passed and which are waiting to be handed out. */
    private final Bucket<K, V> ready = new Bucket<>(null);

    /** Ticks elapsed since {@link #startNanos}, as of the last advance. Never decreases. */
    private long currentTick;

    /** Total tracked nodes: filed in the wheel plus waiting in {@link #ready}. */
    private int size;

    /** Nodes filed in wheel buckets (excludes {@link #ready}). */
    private int wheelResident;

    /**
     * @param tickNanos  the width of a level-0 slot, in nanoseconds; must be positive
     * @param wheelSize  slots per level; must be a power of two, at least 2
     * @param startNanos the time origin; every tick count is measured from here
     */
    public HierarchicalTimingWheel(long tickNanos, int wheelSize, long startNanos) {
        if (tickNanos < 1) {
            throw new IllegalArgumentException("tickNanos must be positive, got " + tickNanos);
        }
        if (wheelSize < 2 || Integer.bitCount(wheelSize) != 1) {
            throw new IllegalArgumentException("wheelSize must be a power of two >= 2, got " + wheelSize);
        }
        this.tickNanos = tickNanos;
        this.wheelSize = wheelSize;
        this.mask = wheelSize - 1;
        this.startNanos = startNanos;
        levels.add(new Level<>(1, wheelSize));
    }

    // ------------------------------------------------------------------
    //  Queries
    // ------------------------------------------------------------------

    /** @return the number of tracked nodes. @implNote O(1) */
    public int size() {
        return size;
    }

    /** @return whether {@code node} is tracked. @implNote O(1) */
    public boolean contains(Node<K, V> node) {
        return node.wheelBucket != null;
    }

    // ------------------------------------------------------------------
    //  Scheduling
    // ------------------------------------------------------------------

    /**
     * Tracks {@code node} until its {@link Node#expiresAtNanos()}, or moves it
     * if it is already tracked.
     *
     * @param node a node with a deadline
     * @implNote O(levels), effectively O(1)
     */
    public void schedule(Node<K, V> node) {
        if (node.wheelBucket != null) {
            unlink(node);                // reschedule: file it afresh below
            size--;
        }
        size++;
        place(node);
    }

    /**
     * Stops tracking {@code node}.
     *
     * @param node any node
     * @return whether it was tracked
     * @implNote O(1) -- the node knows its own bucket and neighbours
     */
    public boolean cancel(Node<K, V> node) {
        if (node.wheelBucket == null) {
            return false;
        }
        unlink(node);
        size--;
        return true;
    }

    /**
     * Files {@code node} in the correct level and slot for its deadline.
     *
     * <p>The level is the finest one whose reach covers the deadline. Reach is
     * measured in <i>slots ahead of the hand at that level's own granularity</i>:
     * a deadline that lands fewer than {@code wheelSize} slots away fits there.
     */
    private void place(Node<K, V> node) {
        long due = ticksAt(node.expiresAtNanos);
        if (due < currentTick) {
            // Already overdue. File it under the current tick, where the next
            // poll will find it and check it against the exact clock.
            due = currentTick;
        }

        int level = 0;
        long width = 1;
        while (due / width - currentTick / width >= wheelSize) {
            level++;
            width *= wheelSize;          // safe: reaching here implies width * wheelSize <= due <= 2^62
        }

        Bucket<K, V> bucket = levelAt(level).buckets[(int) ((due / width) & mask)];
        push(bucket, node);
    }

    private Level<K, V> levelAt(int index) {
        while (levels.size() <= index) {
            long width = levels.get(levels.size() - 1).width * wheelSize;
            levels.add(new Level<>(width, wheelSize));
        }
        return levels.get(index);
    }

    /** Converts an absolute deadline to ticks since the start, clamped to [0, 2^62]. */
    private long ticksAt(long nanos) {
        long elapsed = nanos - startNanos;      // subtraction: correct across a nanoTime wrap
        if (elapsed <= 0) {
            return 0;
        }
        return Math.min(elapsed / tickNanos, MAX_TICK);
    }

    // ------------------------------------------------------------------
    //  Expiry
    // ------------------------------------------------------------------

    /**
     * Removes and returns one node whose deadline is at or before
     * {@code nowNanos}, or {@code null} if none is due. Never returns a node
     * early. Callers loop until it returns {@code null} to drain everything due.
     *
     * @param nowNanos the current time
     * @return a due node, now untracked; or {@code null}
     * @implNote amortised O(1) plus a scan of the current tick's bucket
     */
    public Node<K, V> pollExpired(long nowNanos) {
        // Anything already found due is handed out first, with no further work.
        if (ready.head == null) {
            long nowTick = ticksAt(nowNanos);
            if (nowTick > currentTick) {
                advanceTo(nowTick);
            }
            scanCurrentBucket(nowNanos);
        }

        Node<K, V> due = ready.head;
        if (due == null) {
            return null;
        }
        unlink(due);
        size--;
        return due;
    }

    /**
     * Moves the hand forward to {@code targetTick}, cascading coarse slots into
     * finer levels as their boundaries are crossed and collecting everything
     * that has fully passed into {@link #ready}.
     */
    private void advanceTo(long targetTick) {
        while (currentTick < targetTick) {
            // We are leaving this tick, so everything filed under it is now in the past.
            drainToReady(currentBucket());

            if (wheelResident == 0) {
                currentTick = targetTick;     // nothing left in the wheel: nothing can happen
                return;
            }

            // Skip empty time. If levels 0..k-1 are empty, no slot at those levels
            // can fire, and cascades only occur at multiples of width(k), so the
            // hand can jump directly to the next such multiple.
            int k = 0;
            long stride = 1;
            while (levels.get(k).count == 0) {
                stride *= wheelSize;
                k++;
            }
            long next = (currentTick / stride + 1) * stride;
            if (next > targetTick) {
                currentTick = targetTick;     // no boundary before the target
                return;
            }

            currentTick = next;
            cascade(next);
        }
    }

    /**
     * At tick {@code t}, every level whose slot width divides {@code t} has
     * just rolled onto a new slot. Empty each such slot and re-file its nodes;
     * they now fall within reach of a finer level.
     */
    private void cascade(long t) {
        int top = 0;
        for (int i = 1; i < levels.size(); i++) {
            if (t % levels.get(i).width != 0) {
                break;                        // widths nest, so no higher level can divide t either
            }
            top = i;
        }
        for (int i = top; i >= 1; i--) {
            Level<K, V> level = levels.get(i);
            Bucket<K, V> slot = level.buckets[(int) ((t / level.width) & mask)];
            Node<K, V> node;
            while ((node = slot.head) != null) {
                unlink(node);
                place(node);                  // lands in a finer level, at or after tick t
            }
        }
    }

    /** Collects nodes in the current tick's bucket whose deadline has actually been reached. */
    private void scanCurrentBucket(long nowNanos) {
        Bucket<K, V> bucket = currentBucket();
        Node<K, V> node = bucket.head;
        while (node != null) {
            Node<K, V> next = node.wheelNext;
            if (nowNanos - node.expiresAtNanos >= 0) {   // exact check: never early
                unlink(node);
                push(ready, node);
            }
            node = next;
        }
    }

    private Bucket<K, V> currentBucket() {
        return levels.get(0).buckets[(int) (currentTick & mask)];
    }

    private void drainToReady(Bucket<K, V> bucket) {
        Node<K, V> node;
        while ((node = bucket.head) != null) {
            unlink(node);
            push(ready, node);
        }
    }

    // ------------------------------------------------------------------
    //  Bucket plumbing
    // ------------------------------------------------------------------

    private void push(Bucket<K, V> bucket, Node<K, V> node) {
        node.wheelPrev = null;
        node.wheelNext = bucket.head;
        if (bucket.head != null) {
            bucket.head.wheelPrev = node;
        }
        bucket.head = node;
        node.wheelBucket = bucket;
        bucket.count++;
        if (bucket.owner != null) {
            bucket.owner.count++;
            wheelResident++;
        }
    }

    @SuppressWarnings("unchecked")
    private void unlink(Node<K, V> node) {
        Bucket<K, V> bucket = (Bucket<K, V>) node.wheelBucket;
        if (node.wheelPrev != null) {
            node.wheelPrev.wheelNext = node.wheelNext;
        } else {
            bucket.head = node.wheelNext;
        }
        if (node.wheelNext != null) {
            node.wheelNext.wheelPrev = node.wheelPrev;
        }
        node.wheelPrev = null;
        node.wheelNext = null;
        node.wheelBucket = null;
        bucket.count--;
        if (bucket.owner != null) {
            bucket.owner.count--;
            wheelResident--;
        }
    }

    /** Stops tracking everything. @implNote O(n + slots) so no node keeps a stale bucket pointer. */
    public void clear() {
        for (Level<K, V> level : levels) {
            for (Bucket<K, V> bucket : level.buckets) {
                drain(bucket);
            }
        }
        drain(ready);
        size = 0;
    }

    private void drain(Bucket<K, V> bucket) {
        Node<K, V> node;
        while ((node = bucket.head) != null) {
            unlink(node);
        }
    }

    // ------------------------------------------------------------------
    //  Self-check
    // ------------------------------------------------------------------

    /**
     * Verifies the wheel is consistent. Does nothing unless
     * {@code -Dvelox.assertions=true}.
     *
     * <p>Beyond the linked-list plumbing, this checks that <b>every node sits in
     * the slot its deadline dictates</b>. A node filed one slot off would still
     * pass every structural check but fire at the wrong time, so this is the
     * check that guards the algorithm itself:
     *
     * <ul>
     *   <li>level 0, current slot: only overdue-or-current nodes (deadline tick at most now)</li>
     *   <li>level 0, other slots: deadline within the next {@code wheelSize - 1} ticks, in that slot</li>
     *   <li>level n &gt;= 1: at least one and fewer than {@code wheelSize} slots ahead of the hand
     *       at that level's granularity, in the slot its deadline indexes</li>
     * </ul>
     *
     * @throws IllegalStateException if the wheel is inconsistent
     */
    public void assertInvariants() {
        if (!Invariants.ENABLED) {
            return;
        }

        int total = 0;
        int resident = 0;

        for (int li = 0; li < levels.size(); li++) {
            Level<K, V> level = levels.get(li);
            int levelTotal = 0;

            for (int slot = 0; slot < wheelSize; slot++) {
                Bucket<K, V> bucket = level.buckets[slot];
                int counted = 0;
                Node<K, V> previous = null;

                for (Node<K, V> n = bucket.head; n != null; n = n.wheelNext) {
                    Invariants.check(n.wheelPrev == previous, "broken back-pointer in level " + li + " slot " + slot);
                    Invariants.check(n.wheelBucket == bucket, "node " + n.key + " points at a different bucket");
                    Invariants.check(n.hasDeadline, "node " + n.key + " is in the wheel without a deadline");
                    previous = n;
                    counted++;
                    Invariants.check(counted <= size, "bucket longer than the whole wheel -- cycle?");

                    long due = ticksAt(n.expiresAtNanos);
                    if (li == 0) {
                        if (slot == (int) (currentTick & mask)) {
                            Invariants.check(due <= currentTick,
                                    "node " + n.key + " in the current slot is due at tick " + due
                                            + ", after now (" + currentTick + ")");
                        } else {
                            Invariants.check(due > currentTick && due - currentTick < wheelSize
                                            && (due & mask) == slot,
                                    "node " + n.key + " due at tick " + due + " is misfiled in level-0 slot "
                                            + slot + " (now " + currentTick + ")");
                        }
                    } else {
                        long ahead = due / level.width - currentTick / level.width;
                        Invariants.check(ahead >= 1 && ahead < wheelSize
                                        && ((due / level.width) & mask) == slot,
                                "node " + n.key + " due at tick " + due + " is misfiled in level " + li
                                        + " slot " + slot + " (now " + currentTick + ")");
                    }
                }
                Invariants.check(counted == bucket.count,
                        "bucket count " + bucket.count + " but " + counted + " nodes are linked");
                levelTotal += counted;
            }
            Invariants.check(levelTotal == level.count, "level " + li + " count is out of step");
            resident += levelTotal;
            total += levelTotal;
        }

        int waiting = 0;
        Node<K, V> previous = null;
        for (Node<K, V> n = ready.head; n != null; n = n.wheelNext) {
            Invariants.check(n.wheelPrev == previous && n.wheelBucket == ready, "broken ready list");
            previous = n;
            waiting++;
        }
        Invariants.check(waiting == ready.count, "ready count is out of step");
        total += waiting;

        Invariants.check(resident == wheelResident, "wheelResident is out of step");
        Invariants.check(total == size, "wheel holds " + total + " nodes but size says " + size);
    }
}
