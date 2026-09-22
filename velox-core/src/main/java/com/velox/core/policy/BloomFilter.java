package com.velox.core.policy;

import com.velox.core.util.Hashing;

import java.util.Arrays;

/**
 * A <b>Bloom filter</b>: a probabilistic "have I seen this?" set that never gives a false
 * "no", occasionally gives a false "yes", and costs a few bits per key instead of storing
 * the key at all.
 *
 * <h2>The idea</h2>
 *
 * A bit array, and {@code k} hash functions per key. {@link #add} sets the {@code k} bits
 * that {@code key} hashes to. {@link #mightContain} checks whether all {@code k} of them are
 * set. If any is 0, the key was <b>definitely never added</b> — nothing else could have set
 * that bit, since adding always sets every one of a key's own bits. If all {@code k} are 1,
 * the key was <b>probably</b> added — or every one of its bits happens to have been set by
 * some combination of other keys, a false positive. The false-positive rate falls as the bit
 * array grows relative to the number of keys stored, and rises as more keys are added; it is
 * a deliberate space/accuracy trade, not a bug.
 *
 * <p>What a Bloom filter <b>cannot</b> do is name what it has seen (there are no keys stored,
 * only bits) or forget one key without risking others (clearing a bit that several keys share
 * would break the "never a false no" guarantee for all of them). It answers exactly one
 * question, in return for using almost no memory to do it.
 *
 * <h2>The "doorkeeper" this project uses it for</h2>
 *
 * {@link TinyLfuPolicy} does not want to spend a {@link CountMinSketch} increment (and the
 * bookkeeping that comes with it) on a key that turns out to be a one-hit wonder — most web
 * traffic is full of them, and letting them all touch the frequency sketch just adds noise
 * that a genuine repeat visitor then has to overcome. {@link #seenBefore} is built for
 * exactly this: on a key's first appearance it records the key here and reports "no, this is
 * new"; only once a key comes back does {@link #seenBefore} report "yes", the doorkeeper's
 * signal that this key has earned a place in the real frequency count.
 *
 * @see CountMinSketch what a key graduates to after {@link #seenBefore} says yes
 */
final class BloomFilter {

    private final long[] bits;
    private final int bitMask;
    private final int hashFunctions;

    /**
     * @param expectedInsertions how many distinct keys this filter should hold comfortably
     * @param hashFunctions      how many bits each key sets; more lowers the false-positive
     *                           rate up to a point, then makes it worse by filling the array
     *                           faster than it helps. 4-7 is the usual sweet spot.
     */
    BloomFilter(int expectedInsertions, int hashFunctions) {
        if (expectedInsertions < 1) {
            throw new IllegalArgumentException("expectedInsertions must be at least 1, got " + expectedInsertions);
        }
        if (hashFunctions < 1) {
            throw new IllegalArgumentException("hashFunctions must be at least 1, got " + hashFunctions);
        }
        // ~8 bits per expected key is the standard rule of thumb for a single-digit-percent
        // false-positive rate at 4-6 hash functions.
        int bitCount = Hashing.nextPowerOfTwo(Math.max(64, expectedInsertions * 8));
        this.bitMask = bitCount - 1;
        this.bits = new long[bitCount / Long.SIZE];
        this.hashFunctions = hashFunctions;
    }

    /** Sets {@code key}'s bits. @implNote O(hashFunctions) */
    void add(Object key) {
        int h1 = Hashing.spread(key);
        int h2 = Hashing.mix(h1);
        for (int i = 0; i < hashFunctions; i++) {
            setBit(indexFor(h1, h2, i));
        }
    }

    /**
     * @return {@code false} if {@code key} was definitely never {@link #add}ed; {@code true}
     *         if it probably was (see the class documentation for the false-positive trade)
     * @implNote O(hashFunctions)
     */
    boolean mightContain(Object key) {
        int h1 = Hashing.spread(key);
        int h2 = Hashing.mix(h1);
        for (int i = 0; i < hashFunctions; i++) {
            if (!getBit(indexFor(h1, h2, i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The doorkeeper operation: checks {@code key}, adding it if this looks like its first
     * appearance.
     *
     * @return whether {@code key} appears to have been seen before ({@link #mightContain}
     *         was already true for it, with the same false-positive caveat)
     * @implNote O(hashFunctions), one pass: it checks and records each bit together, rather
     *           than calling {@link #mightContain} then {@link #add} and hashing twice
     */
    boolean seenBefore(Object key) {
        int h1 = Hashing.spread(key);
        int h2 = Hashing.mix(h1);
        boolean allAlreadySet = true;
        for (int i = 0; i < hashFunctions; i++) {
            int index = indexFor(h1, h2, i);
            if (!getBit(index)) {
                allAlreadySet = false;
                setBit(index);
            }
        }
        return allAlreadySet;
    }

    /** Forgets every key. @implNote O(bit array size) */
    void clear() {
        Arrays.fill(bits, 0L);
    }

    /** Kirsch-Mitzenmacher double hashing: see {@link CountMinSketch#column}. */
    private int indexFor(int h1, int h2, int i) {
        return (h1 + i * h2) & bitMask;
    }

    private void setBit(int index) {
        bits[index >>> 6] |= 1L << (index & 63);
    }

    private boolean getBit(int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }
}
