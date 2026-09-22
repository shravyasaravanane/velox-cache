package com.velox.core.sketch;

/**
 * A <b>HyperLogLog</b>: estimates how many <i>distinct</i> values have been added, in a fixed,
 * small amount of memory -- a few kilobytes regardless of whether ten or ten billion values
 * were added, and regardless of how many times each one repeats.
 *
 * <h2>The problem it solves</h2>
 *
 * Counting distinct keys exactly needs a set holding every key ever seen -- unbounded memory,
 * the same problem {@link com.velox.core.policy.CountMinSketch} solves for frequency instead of
 * distinctness. This project's live dashboard wants "how many distinct products has this server
 * been asked for" as a running number; keeping an exact {@code HashSet<Long>} for that would
 * cost as much memory as the thing being cached was supposed to save.
 *
 * <h2>The trick: hash, then count leading zeros</h2>
 *
 * Hash every added value to a uniformly random 64-bit number. A run of {@code k} leading zero
 * bits in a random number has probability {@code 2^-k} -- so if the <i>longest</i> run of
 * leading zeros seen across every hash so far is {@code k}, that is evidence that roughly
 * {@code 2^k} distinct values have been hashed (it takes about that many independent coin
 * flips before one comes up "k heads in a row"). A single register recording the longest run
 * seen is a wildly noisy estimator on its own, but averaging many independent registers -- each
 * fed a disjoint slice of the hash space -- cancels out most of that noise.
 *
 * <h2>Splitting the hash into an index and a rank</h2>
 *
 * The top {@link #precision} bits of each 64-bit hash select one of {@code m = 2^precision}
 * registers; the remaining bits are what the leading-zero count is measured on. Each register
 * keeps the largest rank (leading-zero count + 1) ever routed to it. {@link #estimate()}
 * combines all {@code m} registers with the harmonic mean, which -- unlike an arithmetic mean --
 * is dominated by the smallest values, exactly compensating for how a few registers that
 * happened to see an unusually long run would otherwise skew a plain average upward.
 *
 * <h2>What "fixed memory" costs in accuracy</h2>
 *
 * The standard error of the estimate is about {@code 1.04 / sqrt(m)}. At the default precision
 * (14, so {@code m = 16384} one-byte registers, 16 KB), that is about 0.8%. This is a genuine,
 * disclosed trade-off, the same kind {@link com.velox.core.policy.CountMinSketch}'s Javadoc
 * makes: an approximate answer in kilobytes instead of an exact one in megabytes.
 *
 * <h2>Small-cardinality correction</h2>
 *
 * When few distinct values have been added, most registers are still at zero and the harmonic-
 * mean estimator is biased. Below {@code 2.5m}, {@link #estimate()} instead uses linear
 * counting -- {@code m * ln(m / zeros)}, derived from the fraction of registers still untouched
 * -- which is accurate exactly where the main estimator is weak.
 *
 * <h2>Why a 64-bit hash, not {@code Hashing.spread}</h2>
 *
 * {@link com.velox.core.util.Hashing#spread} is a 32-bit avalanche mix built for slotting into
 * a hash table -- plenty of entropy for that, not enough here. This class needs enough bits
 * that "top {@code precision} bits" and "leading zeros of the rest" both have room to be
 * meaningful at once, so it mixes with the SplitMix64 finalizer (also used inside the JDK's own
 * {@link java.util.SplittableRandom}) directly on the raw {@code long}, never narrowing through
 * a 32-bit {@code hashCode()} first.
 */
public final class HyperLogLog {

    private static final int MIN_PRECISION = 4;
    private static final int MAX_PRECISION = 16;
    private static final int DEFAULT_PRECISION = 14;

    private final int precision;
    private final int registerCount;
    private final byte[] registers;

    /** A HyperLogLog at the default precision (14: ~0.8% standard error, 16 KB). */
    public HyperLogLog() {
        this(DEFAULT_PRECISION);
    }

    /**
     * @param precision how many top hash bits select a register; {@code m = 2^precision}
     *                   registers are allocated, one byte each. Standard error is about
     *                   {@code 1.04 / sqrt(m)}: precision 10 (1 KB) is about 3.3% error,
     *                   precision 14 (16 KB, the default) is about 0.8%, precision 16 (64 KB)
     *                   is about 0.4%.
     * @throws IllegalArgumentException if precision is outside [4, 16]
     */
    public HyperLogLog(int precision) {
        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IllegalArgumentException(
                    "precision must be between " + MIN_PRECISION + " and " + MAX_PRECISION + ", got " + precision);
        }
        this.precision = precision;
        this.registerCount = 1 << precision;
        this.registers = new byte[registerCount];
    }

    /**
     * Records one occurrence of {@code value}. Adding the same value any number of times has
     * the same effect as adding it once -- that is the entire point of a distinct-count
     * estimator.
     *
     * @implNote O(1)
     */
    public void add(long value) {
        long hash = mix64(value);
        int index = (int) (hash >>> (64 - precision));
        long remaining = hash << precision;
        int rank = Math.min(64 - precision + 1, Long.numberOfLeadingZeros(remaining) + 1);
        if (rank > registers[index]) {
            registers[index] = (byte) rank;
        }
    }

    /**
     * @return the estimated number of distinct values added so far
     * @implNote O(m), where {@code m = 2^precision} -- one pass over the registers
     */
    public long estimate() {
        double indicatorSum = 0.0;
        int zeroRegisters = 0;
        for (byte register : registers) {
            indicatorSum += 1.0 / (1L << register);
            if (register == 0) {
                zeroRegisters++;
            }
        }

        double rawEstimate = alpha(registerCount) * registerCount * (double) registerCount / indicatorSum;

        if (rawEstimate <= 2.5 * registerCount && zeroRegisters > 0) {
            return Math.round(registerCount * Math.log((double) registerCount / zeroRegisters));
        }
        return Math.round(rawEstimate);
    }

    /**
     * The bias-correction constant from Flajolet et al., 2007. Converges to
     * {@code 0.7213 / (1 + 1.079/m)} for large {@code m}; the original paper gives exact
     * constants for the three small cases where that asymptotic formula is not yet accurate.
     */
    private static double alpha(int m) {
        return switch (m) {
            case 16 -> 0.673;
            case 32 -> 0.697;
            case 64 -> 0.709;
            default -> 0.7213 / (1 + 1.079 / m);
        };
    }

    /**
     * The SplitMix64 finalizer: three shift-XOR-multiply rounds with odd (hence invertible,
     * hence information-preserving) constants, giving full 64-bit avalanche -- flipping any
     * input bit flips roughly half the output bits.
     */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
