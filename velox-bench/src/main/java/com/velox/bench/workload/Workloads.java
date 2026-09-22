package com.velox.bench.workload;

import java.util.SplittableRandom;

/**
 * The catalogue of request patterns the benchmark lab races every policy against.
 *
 * <p>No single pattern tells the whole story — that is the point of having seven. A policy
 * that wins on {@link #zipfian} can still score 0% on {@link #loop}, and a policy built to
 * survive {@link #scanFlood} may spend memory {@link #uniform} never rewards. The
 * <i>shape</i> of results across every workload is the actual finding; any one row in
 * isolation is not.
 */
public final class Workloads {

    private Workloads() {
    }

    /**
     * Every key equally likely. The baseline that makes every other pattern's skew visible:
     * with no locality at all, hit rate is governed entirely by {@code capacity / keySpace},
     * and no policy can beat any other by more than noise.
     */
    public static Workload uniform(int keySpace) {
        requirePositive(keySpace, "keySpace");
        return new Workload("uniform", keySpace, (seed, length) -> {
            var random = new SplittableRandom(seed);
            int[] out = new int[length];
            for (int i = 0; i < length; i++) {
                out[i] = random.nextInt(keySpace);
            }
            return out;
        });
    }

    /**
     * Rank {@code r} requested with probability proportional to {@code 1/r^theta}: a few keys
     * dominate, as in real web traffic. {@code theta} around 0.9-1.0 is the usual choice for
     * "realistic"; higher concentrates traffic further, lower moves towards {@link #uniform}.
     * Sampled in O(1) per key via {@link AliasSampler}.
     */
    public static Workload zipfian(int keySpace, double theta) {
        requirePositive(keySpace, "keySpace");
        if (theta <= 0) {
            throw new IllegalArgumentException("theta must be positive, got " + theta);
        }
        return new Workload("zipf(" + theta + ")", keySpace, (seed, length) -> {
            var random = new SplittableRandom(seed);
            var sampler = AliasSampler.zipf(keySpace, theta);
            int[] out = new int[length];
            for (int i = 0; i < length; i++) {
                out[i] = sampler.sample(random);
            }
            return out;
        });
    }

    /**
     * One monotonic pass through {@code keySpace}, wrapping if the trace is longer: a full
     * table scan or a backup job. Every key is equally "cold" from the cache's point of
     * view, since none recurs until the whole space has been walked once.
     */
    public static Workload scan(int keySpace) {
        requirePositive(keySpace, "keySpace");
        return new Workload("scan", keySpace, (seed, length) -> {
            int[] out = new int[length];
            for (int i = 0; i < length; i++) {
                out[i] = i % keySpace;
            }
            return out;
        });
    }

    /**
     * The same walk as {@link #scan}, named for a different intent: size {@code loopSize} to
     * {@code capacity + 1} and every deterministic policy scores <b>0%</b> — every key is
     * evicted on the request immediately before it is needed again. This is the concrete
     * example {@link com.velox.core.policy.EvictionPolicy}'s documentation describes; running
     * it is how the benchmark lab proves the claim rather than just asserting it.
     */
    public static Workload loop(int loopSize) {
        requirePositive(loopSize, "loopSize");
        return new Workload("loop(" + loopSize + ")", loopSize, (seed, length) -> {
            int[] out = new int[length];
            for (int i = 0; i < length; i++) {
                out[i] = i % loopSize;
            }
            return out;
        });
    }

    /**
     * The working set itself moves: every {@code shiftPeriod} requests, a fresh, disjoint
     * window of {@code hotSize} keys (wrapping around {@code keySpace}) becomes the only one
     * requested, uniformly within it. This is what {@link com.velox.core.policy.LfuPolicy}'s
     * aging exists for — a policy that only ever remembers "popular so far" is exactly wrong
     * once the hot set has moved on, and this workload is where that shows up as a measured
     * hit-rate gap rather than a hand-wave.
     */
    public static Workload hotSetShift(int keySpace, int hotSize, int shiftPeriod) {
        requirePositive(keySpace, "keySpace");
        requirePositive(hotSize, "hotSize");
        requirePositive(shiftPeriod, "shiftPeriod");
        if (hotSize > keySpace) {
            throw new IllegalArgumentException("hotSize (" + hotSize + ") cannot exceed keySpace (" + keySpace + ")");
        }
        String name = "hot-set-shift(hot=" + hotSize + ",period=" + shiftPeriod + ")";
        return new Workload(name, keySpace, (seed, length) -> {
            var random = new SplittableRandom(seed);
            int[] out = new int[length];
            for (int i = 0; i < length; i++) {
                int period = i / shiftPeriod;
                int windowStart = (int) ((long) period * hotSize % keySpace);
                out[i] = (windowStart + random.nextInt(hotSize)) % keySpace;
            }
            return out;
        });
    }

    /**
     * Two disjoint pools requested at different rates: {@code hotShare} of requests land
     * uniformly in a small hot pool, the rest spread uniformly over a much larger cold pool.
     * Unlike {@link #zipfian}'s smooth curve, this is a sharp step — the cleanest possible
     * separation between "worth keeping" and "noise" — and the natural workload for showing
     * what an admission policy (W-TinyLFU) buys over one that admits everything (LRU): the
     * cold pool is exactly the one-hit-wonder flood {@link com.velox.core.policy.TinyLfuPolicy}
     * exists to refuse.
     */
    public static Workload twoPool(int hotSize, int coldSize, double hotShare) {
        requirePositive(hotSize, "hotSize");
        requirePositive(coldSize, "coldSize");
        if (!(hotShare >= 0 && hotShare <= 1)) {
            throw new IllegalArgumentException("hotShare must be in [0, 1], got " + hotShare);
        }
        int keySpace = hotSize + coldSize;
        String name = "two-pool(hot=" + hotSize + ",cold=" + coldSize + ",hotShare=" + hotShare + ")";
        return new Workload(name, keySpace, (seed, length) -> {
            var random = new SplittableRandom(seed);
            int[] out = new int[length];
            for (int i = 0; i < length; i++) {
                out[i] = random.nextDouble() < hotShare
                        ? random.nextInt(hotSize)
                        : hotSize + random.nextInt(coldSize);
            }
            return out;
        });
    }

    /**
     * <b>Adversarial</b>: a steady Zipfian hot core, periodically interrupted by a long
     * one-pass scan through a disjoint, never-repeating cold range, then back to the hot
     * core. This is the standard test of <b>scan resistance</b> — real buffer pools
     * (PostgreSQL's, for one) are judged on exactly this scenario. A policy with no admission
     * control admits the entire scan burst at face value and can lose most of its hot working
     * set to it every single time; {@link com.velox.core.policy.SlruPolicy}-style protection
     * and W-TinyLFU's frequency check both exist to survive it.
     *
     * @param hotKeySpace  size of the hot pool, keys {@code [0, hotKeySpace)}
     * @param hotTheta     Zipf skew of the hot core
     * @param coldKeySpace size of the cold pool, keys {@code [hotKeySpace, hotKeySpace + coldKeySpace)}
     * @param burstEvery   hot requests between scan bursts
     * @param burstLength  cold requests per scan burst
     */
    public static Workload scanFlood(int hotKeySpace, double hotTheta, int coldKeySpace, int burstEvery, int burstLength) {
        requirePositive(hotKeySpace, "hotKeySpace");
        requirePositive(coldKeySpace, "coldKeySpace");
        requirePositive(burstEvery, "burstEvery");
        requirePositive(burstLength, "burstLength");
        int keySpace = hotKeySpace + coldKeySpace;
        String name = "scan-flood(hot=" + hotKeySpace + ",cold=" + coldKeySpace
                + ",burstEvery=" + burstEvery + ",burstLength=" + burstLength + ")";
        return new Workload(name, keySpace, (seed, length) -> {
            var random = new SplittableRandom(seed);
            var hotSampler = AliasSampler.zipf(hotKeySpace, hotTheta);
            int[] out = new int[length];
            int i = 0;
            int coldCursor = 0;
            while (i < length) {
                int hotChunk = Math.min(burstEvery, length - i);
                for (int end = i + hotChunk; i < end; i++) {
                    out[i] = hotSampler.sample(random);
                }
                int scanChunk = Math.min(burstLength, length - i);
                for (int end = i + scanChunk; i < end; i++, coldCursor++) {
                    out[i] = hotKeySpace + (coldCursor % coldKeySpace);
                }
            }
            return out;
        });
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, got " + value);
        }
    }
}
