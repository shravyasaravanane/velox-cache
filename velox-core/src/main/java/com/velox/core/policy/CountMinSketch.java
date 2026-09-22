package com.velox.core.policy;

import com.velox.core.util.Hashing;

import java.util.Arrays;

/**
 * A <b>Count-Min Sketch</b>: an approximate frequency counter for an unbounded number of
 * keys, in a fixed, small amount of memory.
 *
 * <h2>The problem it solves</h2>
 *
 * {@link LfuPolicy} counts frequency exactly, one field per resident {@code Node} — free,
 * because it only ever counts entries the cache is already paying to store. But
 * {@link TinyLfuPolicy}, the policy this class exists for, needs to compare the frequency
 * of a candidate that is <b>not yet in the cache</b> against the frequency of the entry it
 * would evict, so it can refuse admission to the newcomer if it loses that comparison. That
 * needs a frequency count for every key ever seen, including ones never admitted — millions
 * of them, in a busy cache — which an exact per-key counter cannot afford.
 *
 * <h2>The trick: count in rows, and believe the smallest one</h2>
 *
 * A sketch is a small 2-D table of counters, {@code depth} rows by {@code width} columns.
 * {@link #increment} hashes the key once per row (a different hash per row, so unrelated
 * keys collide differently in each one) and bumps the counter at that row's column.
 * {@link #estimate} does the same lookups and returns the <b>minimum</b> of the {@code depth}
 * values found.
 *
 * <p>Collisions can only ever make a counter <i>larger</i> than the truth (two keys sharing
 * a slot both add to it), never smaller. So every row's counter is an overestimate, and the
 * true count is never above the smallest one — taking the minimum across independently
 * hashed rows cancels out whichever row got unlucky. This is what makes the structure
 * "conservative" in the sense that matters here: it can overstate a key's popularity, but
 * it never understates it.
 *
 * <h2>Conservative update</h2>
 *
 * {@link #increment} does not simply add 1 to all {@code depth} counters. It first finds
 * their current minimum, then increments <b>only</b> the counters already at that minimum.
 * A counter that is already inflated by an unrelated collision is left alone — it does not
 * need to get even more wrong. This measurably reduces overestimation for the same memory,
 * at the cost of a counter occasionally lagging by one until its row's minimum catches up
 * (harmless, since {@link #estimate} takes the minimum anyway).
 *
 * <h2>Tiny, saturating counters</h2>
 *
 * Each counter is 4 bits (0-15), sixteen packed into every {@code long}. A byte-wide or
 * {@code int}-wide counter would waste memory the sketch's whole reason for existing is to
 * avoid: with 4-bit counters, a sketch sized for a million keys costs a few hundred
 * kilobytes instead of several megabytes. A counter saturates at 15 and simply stops
 * counting rather than overflow into the next one.
 *
 * <h2>Periodic halving</h2>
 *
 * Popularity measured since the sketch was created would let a key that was hot last week
 * outrank one that is hot right now, forever. Every {@code resetAtCount} increments, every
 * counter is halved (see {@link #halveAll()}), the same aging idea {@link LfuPolicy} already
 * uses, so old popularity decays geometrically while relative order survives.
 *
 * @see BloomFilter the companion structure that keeps one-hit keys out of this sketch entirely
 */
final class CountMinSketch {

    private static final int COUNTERS_PER_LONG = 16;
    private static final long COUNTER_MASK = 0xF;
    private static final long CLEAR_TOP_BIT_OF_EACH_NIBBLE = 0x7777777777777777L;
    private static final int MAX_COUNT = 15;

    private final int depth;
    private final int widthMask;
    private final long[][] table;
    private final long resetAtCount;
    private long additions;

    /**
     * @param width        counters per row; rounded up to a power of two
     * @param depth        number of independently hashed rows; at least 1
     * @param resetAtCount halve every counter after this many increments; 0 disables aging
     */
    CountMinSketch(int width, int depth, long resetAtCount) {
        if (width < 1) {
            throw new IllegalArgumentException("width must be at least 1, got " + width);
        }
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be at least 1, got " + depth);
        }
        if (resetAtCount < 0) {
            throw new IllegalArgumentException("resetAtCount must be >= 0, got " + resetAtCount);
        }
        int roundedWidth = Hashing.nextPowerOfTwo(width);
        this.widthMask = roundedWidth - 1;
        this.depth = depth;
        this.table = new long[depth][(roundedWidth + COUNTERS_PER_LONG - 1) / COUNTERS_PER_LONG];
        this.resetAtCount = resetAtCount;
    }

    /** A sketch sized for roughly {@code expectedInsertions} distinct keys, with sane defaults. */
    CountMinSketch(int expectedInsertions) {
        this(Math.max(8, expectedInsertions), 4, 10L * Math.max(1, expectedInsertions));
    }

    /**
     * Records one occurrence of {@code key}, using conservative update.
     *
     * @implNote O(depth), no allocation
     */
    void increment(Object key) {
        int h1 = Hashing.spread(key);
        int h2 = Hashing.mix(h1);

        int min = MAX_COUNT;
        for (int row = 0; row < depth; row++) {
            min = Math.min(min, get(row, column(h1, h2, row)));
        }
        if (min >= MAX_COUNT) {
            return;                          // every row already saturated: nothing to do
        }
        for (int row = 0; row < depth; row++) {
            int col = column(h1, h2, row);
            if (get(row, col) == min) {
                set(row, col, min + 1);
            }
        }

        if (resetAtCount > 0 && ++additions >= resetAtCount) {
            halveAll();
            additions = 0;
        }
    }

    /**
     * @return the estimated number of times {@code key} has been recorded: never less than
     *         the true count, and usually equal to it
     * @implNote O(depth), no allocation
     */
    int estimate(Object key) {
        int h1 = Hashing.spread(key);
        int h2 = Hashing.mix(h1);

        int min = MAX_COUNT;
        for (int row = 0; row < depth; row++) {
            min = Math.min(min, get(row, column(h1, h2, row)));
        }
        return min;
    }

    /** Forgets every count. @implNote O(width * depth) */
    void clear() {
        for (long[] row : table) {
            Arrays.fill(row, 0L);
        }
        additions = 0;
    }

    /**
     * Which column {@code row} maps {@code key} to, from two base hashes.
     *
     * <p>Deriving {@code depth} column choices from just two hashes (Kirsch-Mitzenmacher
     * double hashing, {@code h1 + row * h2}) rather than hashing the key {@code depth}
     * separate times is standard practice for sketches and Bloom filters alike: it is
     * provably about as good in practice, and it is one hash computation instead of several.
     */
    private int column(int h1, int h2, int row) {
        return (h1 + row * h2) & widthMask;
    }

    private int get(int row, int col) {
        int longIndex = col / COUNTERS_PER_LONG;
        int shift = (col % COUNTERS_PER_LONG) * 4;
        return (int) ((table[row][longIndex] >>> shift) & COUNTER_MASK);
    }

    private void set(int row, int col, int value) {
        int longIndex = col / COUNTERS_PER_LONG;
        int shift = (col % COUNTERS_PER_LONG) * 4;
        long cleared = table[row][longIndex] & ~(COUNTER_MASK << shift);
        table[row][longIndex] = cleared | ((long) value << shift);
    }

    /**
     * Halves all sixteen 4-bit counters in every {@code long} with three word-wide
     * operations instead of sixteen small ones.
     *
     * <p>Shifting the whole word right by one bit divides every nibble by two, but it also
     * leaks the lowest bit of nibble N+1 into the top bit of nibble N. Since a correctly
     * halved 4-bit value (max 15 -&gt; 7) never needs its top bit anyway, masking it back to
     * zero afterwards discards exactly the leaked bits and nothing else.
     */
    private void halveAll() {
        for (long[] row : table) {
            for (int i = 0; i < row.length; i++) {
                row[i] = (row[i] >>> 1) & CLEAR_TOP_BIT_OF_EACH_NIBBLE;
            }
        }
    }
}
