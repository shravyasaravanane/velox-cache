package com.velox.server.loadgen;

import java.util.Arrays;
import java.util.random.RandomGenerator;

/**
 * Draws ids from {@code [0, keySpace)} with a Zipfian skew: a small head of ids gets requested
 * far more often than the long tail, the same popularity shape real catalog traffic has.
 *
 * <h2>Why binary search over cumulative weights, not the alias method</h2>
 *
 * {@code velox-bench}'s always-on simulation loop uses Vose's alias method: an O(n) setup pays
 * for O(1) per draw, which matters when a benchmark takes hundreds of thousands of draws in a
 * tight loop. This is a one-shot CLI tool that draws once per HTTP round trip, so a single draw's
 * cost is irrelevant next to the network call it drives -- the textbook technique the alias
 * method exists to improve on (binary search over a cumulative-weight array, O(log n) per draw)
 * is simpler here and costs nothing measurable in context. See {@code docs/ALGORITHMS.md} and
 * Review 2's technique-selection slides for the alias method itself.
 */
final class ZipfianSampler {

    private final double[] cumulative;
    private final double total;
    private final RandomGenerator random;

    ZipfianSampler(int keySpace, double exponent, RandomGenerator random) {
        if (keySpace < 1) {
            throw new IllegalArgumentException("keySpace must be at least 1, got " + keySpace);
        }
        this.random = random;
        this.cumulative = new double[keySpace];
        double sum = 0.0;
        for (int i = 0; i < keySpace; i++) {
            sum += 1.0 / Math.pow(i + 1, exponent);
            cumulative[i] = sum;
        }
        this.total = sum;
    }

    /** @return a key in {@code [0, keySpace)}, low ids drawn far more often than high ones */
    int sample() {
        double target = random.nextDouble(0, total);
        int index = Arrays.binarySearch(cumulative, target);
        return index >= 0 ? index : -index - 1;
    }
}
