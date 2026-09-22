package com.velox.bench.workload;

import java.util.SplittableRandom;

/**
 * Samples from a discrete distribution in O(1), using Vose's alias method.
 *
 * <h2>The problem</h2>
 *
 * A benchmark needs millions of keys drawn from a skewed (Zipf) distribution. The
 * obvious way is a table of cumulative probabilities and a binary search per draw, which
 * costs O(log n): about 17 steps for 100,000 keys, comparable to the cost of the cache
 * operation being measured, so the sampler would distort the very thing under test.
 *
 * <h2>The alias method</h2>
 *
 * Split the probability mass into {@code n} equal columns, each holding at most TWO
 * outcomes: the column's "own" outcome and one "alias". A draw picks a column uniformly,
 * then flips one biased coin to choose between its two outcomes. Two random numbers and
 * two array reads, whatever {@code n} is.
 *
 * <p>The columns are built in O(n): outcomes with less than an average share ("small")
 * are topped up with mass borrowed from outcomes with more ("large"), and the large one
 * becomes the small one's alias.
 *
 * <h2>Why it matters here</h2>
 *
 * Each benchmark thread now draws its own INDEPENDENT stream. An earlier version had
 * every thread walk one shared, fixed sample from different offsets, which made threads
 * that happened to start close together re-request each other's keys and inflated hit
 * ratios by an amount that varied from run to run.
 */
public final class AliasSampler {

    private final double[] probability;
    private final int[] alias;

    /**
     * @param weights relative weights of the outcomes; need not sum to 1
     */
    public AliasSampler(double[] weights) {
        int n = weights.length;
        double total = 0;
        for (double w : weights) {
            total += w;
        }

        probability = new double[n];
        alias = new int[n];
        double[] scaled = new double[n];
        int[] small = new int[n];
        int[] large = new int[n];
        int smallCount = 0;
        int largeCount = 0;

        for (int i = 0; i < n; i++) {
            scaled[i] = weights[i] * n / total;               // 1.0 is the average column height
            if (scaled[i] < 1.0) {
                small[smallCount++] = i;
            } else {
                large[largeCount++] = i;
            }
        }

        while (smallCount > 0 && largeCount > 0) {
            int lessThanAverage = small[--smallCount];
            int moreThanAverage = large[--largeCount];
            probability[lessThanAverage] = scaled[lessThanAverage];
            alias[lessThanAverage] = moreThanAverage;         // top the column up with the large outcome
            scaled[moreThanAverage] = scaled[moreThanAverage] + scaled[lessThanAverage] - 1.0;
            if (scaled[moreThanAverage] < 1.0) {
                small[smallCount++] = moreThanAverage;
            } else {
                large[largeCount++] = moreThanAverage;
            }
        }
        // Anything left is exactly 1.0 up to rounding: a column that is entirely its own outcome.
        while (largeCount > 0) {
            probability[large[--largeCount]] = 1.0;
        }
        while (smallCount > 0) {
            probability[small[--smallCount]] = 1.0;
        }
    }

    /**
     * @param random the caller's own generator; never shared between threads
     * @return an outcome index, distributed according to the weights
     */
    public int sample(SplittableRandom random) {
        int column = random.nextInt(probability.length);
        return random.nextDouble() < probability[column] ? column : alias[column];
    }

    /** @return a sampler for a Zipf distribution: outcome i has weight 1 / (i+1)^theta */
    public static AliasSampler zipf(int keys, double theta) {
        double[] weights = new double[keys];
        for (int i = 0; i < keys; i++) {
            weights[i] = 1.0 / Math.pow(i + 1, theta);
        }
        return new AliasSampler(weights);
    }
}
