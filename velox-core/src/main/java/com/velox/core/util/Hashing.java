package com.velox.core.util;

/**
 * Hash mixing utilities.
 *
 * <h2>Why we cannot just use {@code key.hashCode()}</h2>
 *
 * Our hash table finds a slot with {@code hash & (capacity - 1)}, which keeps
 * only the <b>lowest</b> bits of the hash. That is fast — a single AND
 * instruction instead of a division — but it means the high bits are thrown
 * away entirely.
 *
 * <p>That is a disaster for badly distributed hash codes, and Java has a very
 * common one: {@code Integer.hashCode()} returns the integer itself.
 *
 * <p>Suppose product IDs are multiples of 16 (1000, 1016, 1032, ...) and the
 * table has 16 slots. Then {@code hash & 15} is <b>0 every single time</b>.
 * Every key piles into slot 0, the probe chain grows to length n, and our
 * "O(1)" hash map has quietly become an O(n) linear scan.
 *
 * <p>This is not a theoretical worry. Sequential IDs, pointers, and timestamps
 * all have structure concentrated in particular bit positions.
 *
 * <h2>The fix: avalanche mixing</h2>
 *
 * {@link #spread(Object)} runs the raw hash through the MurmurHash3 finalizer,
 * which has the <i>avalanche</i> property: flipping any single input bit
 * flips roughly half the output bits, unpredictably. It scrambles the high
 * bits down into the low bits so that whatever structure the original had is
 * destroyed, and {@code & (capacity - 1)} sees something effectively random.
 *
 * <p>Cost: three shifts, three XORs, two multiplies — a couple of nanoseconds,
 * and no branches. Worth it many times over to avoid the clustering.
 */
public final class Hashing {

    private Hashing() {
        // Utility class: never instantiated.
    }

    /**
     * Returns a well-distributed hash for {@code key}.
     *
     * @param key the key to hash (must not be null)
     * @return a scrambled 32-bit hash suitable for masking into a slot index
     * @implNote O(1) — a handful of arithmetic instructions.
     */
    public static int spread(Object key) {
        return mix(key.hashCode());
    }

    /**
     * The MurmurHash3 32-bit finalizer.
     *
     * <p>Each step does a specific job:
     * <ul>
     *   <li>{@code h ^= h >>> 16} folds the top 16 bits down into the bottom
     *       ones, so high-bit information can influence the slot index</li>
     *   <li>the odd multiplier constants spread each bit's influence sideways
     *       across the whole word (they are odd, so the multiplication is
     *       invertible and no information is destroyed)</li>
     *   <li>the further shift/XOR rounds repeat the folding until the
     *       avalanche property holds</li>
     * </ul>
     *
     * <p>{@code >>>} is the <b>unsigned</b> right shift, which feeds in zeros
     * from the left. Using the signed {@code >>} would feed in copies of the
     * sign bit and ruin the mixing for negative hash codes.
     *
     * @param hash a raw, possibly poorly distributed hash code
     * @return the mixed hash
     * @implNote O(1)
     */
    public static int mix(int hash) {
        int h = hash;
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    /**
     * Rounds {@code value} up to the next power of two (minimum 1).
     *
     * <p>Our table length must always be a power of two, because that is what
     * makes {@code hash & (length - 1)} equivalent to {@code hash % length}.
     * For length 16, {@code length - 1} is {@code 0b1111}, so the AND keeps
     * exactly the low 4 bits — a number in 0..15. A bitwise AND takes about
     * one CPU cycle; integer division takes twenty or more, and it sits on the
     * hottest path we have.
     *
     * @param value the minimum required size
     * @return the smallest power of two that is {@code >= value}
     * @implNote O(1)
     */
    public static int nextPowerOfTwo(int value) {
        if (value <= 1) {
            return 1;
        }
        return Integer.highestOneBit(value - 1) << 1;
    }
}
