package com.velox.core.concurrent;

import com.velox.core.util.Hashing;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A counter many threads can increment without fighting over one memory location.
 *
 * <h2>The problem: one hot cache line</h2>
 *
 * A single shared {@code AtomicLong hits} looks harmless. But every core that
 * increments it must first gain exclusive ownership of the 64-byte cache line
 * holding it, pulling the line away from whichever core had it. With sixteen
 * threads hammering one counter, that line ping-pongs between cores continuously,
 * and each increment costs a cross-core round trip instead of one instruction. The
 * cache lookup the counter is merely <i>recording</i> can end up cheaper than the
 * counting of it.
 *
 * <h2>The fix: spread the writes, sum on read</h2>
 *
 * Keep several cells and let each thread mostly touch its own. Incrementing picks a
 * cell from the calling thread; reading adds them all up. Counters are written
 * constantly and read rarely (a dashboard, once a second), so moving the cost from
 * the write path to the read path is exactly the right trade.
 *
 * <h2>The subtle part: false sharing</h2>
 *
 * Giving each thread its own {@code long} is not enough. A {@code long} is 8 bytes
 * and a cache line is 64, so eight adjacent cells share one line, and threads writing
 * <i>different</i> cells still invalidate each other's cached copy. This is
 * <b>false sharing</b>: no data is actually shared, yet the cores contend anyway.
 *
 * <p>The cure is padding: space the cells 128 bytes apart (two cache lines, because
 * many CPUs prefetch lines in adjacent pairs), so no two cells can ever share a line.
 * Padding costs memory, so it is switchable. Constructing with {@code padded = false}
 * exists precisely so a benchmark can measure how much the padding is worth.
 *
 * <h2>Exactness</h2>
 *
 * Every increment is an atomic read-modify-write on its cell, so <b>no increment is
 * ever lost</b> even when two threads collide on the same cell. {@link #sum()} reads
 * the cells one after another without stopping the world, so a sum taken while
 * others are incrementing is a valid count at <i>some</i> moment during the call,
 * not a single instant. Once writers stop, it is exact.
 */
public final class StripedCounter {

    private static final VarHandle CELL = MethodHandles.arrayElementVarHandle(long[].class);

    /** Longs between the start of successive cells when padded: 16 * 8 bytes = 128 bytes. */
    private static final int PADDED_STRIDE = 16;

    /** Extra longs before the first and after the last cell, so neighbouring objects cannot share a line with them. */
    private static final int MARGIN = 16;

    private final long[] cells;
    private final int stripeMask;
    private final int stride;

    /** Creates a padded counter with a stripe count scaled to the number of CPUs. */
    public StripedCounter() {
        this(Hashing.nextPowerOfTwo(Runtime.getRuntime().availableProcessors() * 2), true);
    }

    /**
     * @param stripes how many cells to spread writes over; rounded up to a power of two
     * @param padded  whether to space the cells a cache line pair apart; {@code false}
     *                packs them together, which is smaller and demonstrably slower under contention
     */
    public StripedCounter(int stripes, boolean padded) {
        int count = Hashing.nextPowerOfTwo(Math.max(1, stripes));
        this.stripeMask = count - 1;
        this.stride = padded ? PADDED_STRIDE : 1;
        this.cells = new long[MARGIN + count * stride + (padded ? MARGIN : 0)];
    }

    /** Adds one. @implNote O(1), lock-free. */
    public void increment() {
        add(1);
    }

    /**
     * Adds {@code delta}.
     *
     * @param delta the amount to add
     * @implNote O(1), lock-free
     */
    public void add(long delta) {
        CELL.getAndAdd(cells, MARGIN + stripe() * stride, delta);
    }

    /**
     * @return the total across all cells: exact once writers have stopped, and a
     *         valid count from some moment during the call while they have not
     * @implNote O(stripes)
     */
    public long sum() {
        long total = 0;
        for (int i = 0; i <= stripeMask; i++) {
            total += (long) CELL.getVolatile(cells, MARGIN + i * stride);
        }
        return total;
    }

    /** @return the number of cells the writes are spread over */
    public int stripes() {
        return stripeMask + 1;
    }

    /**
     * Picks this thread's cell. Thread ids are small sequential integers, so they
     * are scrambled first; otherwise consecutive threads would land in consecutive
     * cells and a stripe count of 4 would only ever use the ids' low two bits.
     */
    private int stripe() {
        long id = Thread.currentThread().threadId();
        return Hashing.mix((int) (id ^ (id >>> 32))) & stripeMask;
    }
}
