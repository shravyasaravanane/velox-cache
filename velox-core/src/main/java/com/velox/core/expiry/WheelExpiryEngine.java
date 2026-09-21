package com.velox.core.expiry;

import com.velox.core.structure.HierarchicalTimingWheel;
import com.velox.core.structure.Node;

/**
 * Expiry scheduling backed by a {@link HierarchicalTimingWheel}.
 *
 * <p>A thin adapter, like {@link HeapExpiryEngine}. The difference is in the
 * cost model: scheduling, rescheduling and cancelling are all O(1), where the
 * heap pays O(log n) for each. That matters most with
 * {@code expireAfterAccess}, which reschedules an entry on <i>every cache
 * hit</i>.
 *
 * <p>Like the heap, this engine is <b>exact</b>: an entry is reported as soon
 * as its deadline passes, never before and never after.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class WheelExpiryEngine<K, V> implements ExpiryEngine<K, V> {

    private final HierarchicalTimingWheel<K, V> wheel;

    /**
     * @param tickNanos  the width of the finest slot, in nanoseconds
     * @param wheelSize  slots per level; a power of two, at least 2
     * @param startNanos the wheel's time origin; use the ticker's current reading
     */
    public WheelExpiryEngine(long tickNanos, int wheelSize, long startNanos) {
        this.wheel = new HierarchicalTimingWheel<>(tickNanos, wheelSize, startNanos);
    }

    @Override
    public void schedule(Node<K, V> node) {
        wheel.schedule(node);
    }

    @Override
    public void cancel(Node<K, V> node) {
        wheel.cancel(node);
    }

    @Override
    public Node<K, V> pollExpired(long nowNanos) {
        return wheel.pollExpired(nowNanos);
    }

    @Override
    public boolean isScheduled(Node<K, V> node) {
        return wheel.contains(node);
    }

    @Override
    public int size() {
        return wheel.size();
    }

    @Override
    public void clear() {
        wheel.clear();
    }

    @Override
    public String name() {
        return "TIMING_WHEEL";
    }

    @Override
    public void assertInvariants() {
        wheel.assertInvariants();
    }
}
