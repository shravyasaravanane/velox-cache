package com.velox.core.expiry;

import com.velox.core.structure.Node;

/**
 * Tracks which entries are due to expire, and hands back the ones whose time
 * has come.
 *
 * <h2>A second pluggable seam</h2>
 *
 * Just as {@link com.velox.core.policy.EvictionPolicy} separates <i>storage</i>
 * from <i>eviction order</i>, this separates <i>storage</i> from <i>expiry
 * scheduling</i>. The cache decides <b>what</b> each entry's deadline is; an
 * engine's only job is answering "which entries are due right now?" quickly.
 *
 * <p>Two implementations exist, and the trade-off between them is a measurable
 * result rather than an opinion:
 *
 * <table>
 *   <caption>Expiry engine comparison</caption>
 *   <tr><th></th><th>schedule</th><th>reschedule (on read)</th><th>cancel</th><th>next due</th></tr>
 *   <tr><td>indexed min-heap</td><td>O(log n)</td><td>O(log n)</td><td>O(log n)</td><td>O(1) exact</td></tr>
 *   <tr><td>timing wheel</td><td>O(1)</td><td>O(1)</td><td>O(1)</td><td>coarse, per tick</td></tr>
 * </table>
 *
 * The heap is exact and simple; the wheel is faster when there are many
 * entries and frequent rescheduling, at the price of only firing on tick
 * boundaries.
 *
 * <h2>Contract</h2>
 *
 * The <b>cache owns the deadline</b>: it sets {@link Node#setExpiresAtNanos}
 * first, then calls {@link #schedule}. An engine reads that field but never
 * decides it. Deadlines are compared by subtraction, never {@code <}, so they
 * remain correct if the nanosecond counter wraps.
 *
 * <p>Implementations are <b>not thread-safe</b>; the owner holds the lock.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface ExpiryEngine<K, V> {

    /**
     * Starts tracking {@code node}, or moves it if it is already tracked.
     *
     * <p>The node's deadline must already be set. Calling this again after
     * changing the deadline repositions it, which is how expire-after-access
     * works: every read pushes the deadline forward.
     *
     * @param node a node with a deadline
     */
    void schedule(Node<K, V> node);

    /**
     * Stops tracking {@code node}. Does nothing if it was not tracked, so
     * callers need not check first.
     *
     * @param node the node to stop tracking
     */
    void cancel(Node<K, V> node);

    /**
     * Removes and returns one entry whose deadline is at or before {@code now}.
     *
     * <p>Callers loop until it returns {@code null} to drain everything due.
     *
     * @param nowNanos the current time
     * @return a due node, now untracked; or {@code null} if nothing is due
     */
    Node<K, V> pollExpired(long nowNanos);

    /**
     * @param node any node
     * @return whether this engine is currently tracking it
     */
    boolean isScheduled(Node<K, V> node);

    /** @return the number of tracked entries */
    int size();

    /** Stops tracking everything. */
    void clear();

    /** @return a short display name, e.g. {@code "INDEXED_HEAP"} */
    String name();

    /**
     * Verifies internal consistency. Does nothing unless
     * {@code -Dvelox.assertions=true}.
     *
     * @throws IllegalStateException if the engine's state is corrupt
     */
    void assertInvariants();
}
