package com.velox.core.concurrent;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * A lock-free ring buffer that many threads write to and one thread at a time
 * drains, and which is allowed to throw writes away.
 *
 * <h2>The problem this solves</h2>
 *
 * In an LRU cache a {@code get} is not a read: it moves the entry to the front of the
 * recency list, which is a write to shared structure. So every lookup needs an
 * exclusive lock and all threads queue up single-file behind it: the more threads, the
 * slower the cache.
 *
 * <p>The way out is to notice that the <i>lookup</i> and the <i>reordering</i> do not
 * have to happen together. A reader finds its value, and instead of reordering the
 * list on the spot, drops "I used this entry" into a buffer and returns at once.
 * Someone later applies the whole batch to the list in one go, under the lock, in
 * far fewer lock acquisitions than there were reads.
 *
 * <h2>Why losing records is fine</h2>
 *
 * Recency order is a <i>heuristic for guessing which entries will be used again</i>,
 * not something correctness depends on. If the buffer is full, or two writers collide,
 * the record is simply dropped. The cost is a slightly less accurate eviction order
 * (measured in fractions of a percent of hit ratio); the benefit is that no reader ever
 * waits. This is the central trade-off of Tier 2, and {@link #dropped()} exists so the
 * cost can be reported honestly rather than assumed.
 *
 * <h2>How it works</h2>
 *
 * <pre>
 *   slots:   [ A ][ B ][ C ][   ][   ][   ][   ][   ]
 *              ^ head (readCounter)     ^ tail (writeCounter)
 * </pre>
 *
 * Two ever-increasing counters. A writer <b>claims</b> a slot by compare-and-setting
 * {@code writeCounter} from {@code t} to {@code t+1}, then <b>publishes</b> its item
 * into slot {@code t & mask}. The single drainer reads from {@code readCounter}
 * upward, nulls each slot as it consumes it, and finally advances {@code readCounter}.
 *
 * <ul>
 *   <li><b>Claim and publish are separate steps.</b> A writer can be paused between
 *       them, so the drainer may find a claimed-but-still-empty slot. It stops there and
 *       leaves the rest for the next drain rather than skipping it: skipping would let
 *       a later writer overwrite a slot whose publisher is still on its way.</li>
 *   <li><b>A slot is only reused after it is consumed.</b> Writers refuse to claim when
 *       {@code tail - head >= capacity}, and the drainer advances {@code head} only
 *       <i>after</i> nulling the slots, so a writer that sees the new head knows the
 *       slot is free.</li>
 *   <li><b>A failed claim is dropped, not retried.</b> Retrying under contention is what
 *       makes lock-free structures slow; losing a heuristic record costs almost nothing.</li>
 * </ul>
 *
 * <h2>Threading contract</h2>
 *
 * Any number of threads may {@link #offer}. {@link #drain} must be called by <b>one
 * thread at a time</b>; the cache guarantees that by draining only while holding the
 * shard's write lock.
 *
 * @param <T> the item type
 */
public final class LossyReadBuffer<T> {

    /** The item was recorded. */
    public static final int ACCEPTED = 0;

    /** The item was recorded, and the buffer is now full enough that draining is advisable. */
    public static final int ACCEPTED_DRAIN_ADVISED = 1;

    /** The item was discarded because the buffer was full. */
    public static final int DROPPED_FULL = 2;

    /** The item was discarded because another writer claimed the slot first. */
    public static final int DROPPED_CONTENDED = 3;

    private final AtomicReferenceArray<T> slots;
    private final int mask;
    private final int capacity;
    private final int drainAdvisedAt;

    private final AtomicLong writeCounter = new AtomicLong();
    private volatile long readCounter;

    private final StripedCounter dropped = new StripedCounter(4, true);

    /**
     * @param capacity slots; must be a power of two, at least 2
     */
    public LossyReadBuffer(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2, got " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.slots = new AtomicReferenceArray<>(capacity);
        this.drainAdvisedAt = Math.max(1, capacity / 2);
    }

    /**
     * Tries to record {@code item}. Never blocks and never retries.
     *
     * @param item what to record; must not be {@code null}
     * @return {@link #ACCEPTED}, {@link #ACCEPTED_DRAIN_ADVISED}, {@link #DROPPED_FULL} or
     *         {@link #DROPPED_CONTENDED}
     * @implNote O(1): at most one compare-and-set
     */
    public int offer(T item) {
        long tail = writeCounter.get();
        long head = readCounter;

        if (tail - head >= capacity) {
            dropped.increment();
            return DROPPED_FULL;
        }
        if (!writeCounter.compareAndSet(tail, tail + 1)) {
            dropped.increment();
            return DROPPED_CONTENDED;
        }

        slots.setRelease((int) (tail & mask), item);      // publish AFTER claiming
        return (tail + 1 - head >= drainAdvisedAt) ? ACCEPTED_DRAIN_ADVISED : ACCEPTED;
    }

    /**
     * Hands every published item to {@code consumer}, oldest first, and frees its slot.
     *
     * <p>Stops early at the first slot that has been claimed but not yet published; the
     * remainder is picked up by the next drain.
     *
     * @param consumer receives each item; must not throw
     * @return how many items were consumed
     * @implNote O(items consumed). <b>Single consumer only.</b>
     */
    public int drain(Consumer<? super T> consumer) {
        long head = readCounter;
        long tail = writeCounter.get();
        int consumed = 0;

        try {
            while (head < tail) {
                int index = (int) (head & mask);
                T item = slots.getAcquire(index);
                if (item == null) {
                    break;                               // claimed but not yet published: wait for it
                }
                slots.setRelease(index, null);           // free the slot BEFORE advancing head
                head++;
                consumed++;
                consumer.accept(item);
            }
        } finally {
            readCounter = head;                          // volatile write: writers may now reuse the slots
        }
        return consumed;
    }

    /** @return roughly how many items are waiting; exact only when no writer is active */
    public int size() {
        return (int) Math.max(0, Math.min(capacity, writeCounter.get() - readCounter));
    }

    /** @return the number of slots */
    public int capacity() {
        return capacity;
    }

    /** @return how many offers have been discarded, whether the buffer was full or the claim was contended */
    public long dropped() {
        return dropped.sum();
    }
}
