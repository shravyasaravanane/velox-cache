package com.velox.bench.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every benchmark result rests on this sampler producing the distribution it claims to,
 * so it is checked against the mathematics rather than assumed.
 */
class AliasSamplerTest {

    private static double[] frequencies(AliasSampler sampler, int outcomes, int draws, long seed) {
        var random = new SplittableRandom(seed);
        long[] counts = new long[outcomes];
        for (int i = 0; i < draws; i++) {
            counts[sampler.sample(random)]++;
        }
        double[] result = new double[outcomes];
        for (int i = 0; i < outcomes; i++) {
            result[i] = (double) counts[i] / draws;
        }
        return result;
    }

    @Test
    @DisplayName("frequencies match the weights")
    void matchesTheWeights() {
        double[] weights = {5, 3, 1, 1};                     // expected 0.5, 0.3, 0.1, 0.1
        var sampler = new AliasSampler(weights);

        double[] observed = frequencies(sampler, 4, 4_000_000, 1);

        assertEquals(0.5, observed[0], 0.003);
        assertEquals(0.3, observed[1], 0.003);
        assertEquals(0.1, observed[2], 0.003);
        assertEquals(0.1, observed[3], 0.003);
    }

    @Test
    @DisplayName("a uniform distribution stays uniform")
    void uniform() {
        var sampler = new AliasSampler(new double[]{1, 1, 1, 1, 1});

        double[] observed = frequencies(sampler, 5, 2_500_000, 2);

        for (double f : observed) {
            assertEquals(0.2, f, 0.003);
        }
    }

    @Test
    @DisplayName("an outcome with zero weight is never drawn")
    void zeroWeightNeverDrawn() {
        var sampler = new AliasSampler(new double[]{1, 0, 1});

        double[] observed = frequencies(sampler, 3, 1_000_000, 3);

        assertEquals(0.0, observed[1]);
    }

    @Test
    @DisplayName("Zipf: rank r is drawn in proportion to 1 / r^theta")
    void zipfShape() {
        int keys = 1_000;
        double theta = 0.99;
        var sampler = AliasSampler.zipf(keys, theta);

        double[] observed = frequencies(sampler, keys, 20_000_000, 4);

        // The ratio of two ranks is independent of the normalising constant, so it can be
        // checked directly: rank 1 vs rank 10 should be 10^0.99, about 9.77.
        double ratio = observed[0] / observed[9];
        assertEquals(Math.pow(10, theta), ratio, 0.35);
        assertTrue(observed[0] > observed[1] && observed[1] > observed[10] && observed[10] > observed[500],
                "popularity must fall with rank");
    }

    @Test
    @DisplayName("different generators give different streams, the same seed the same one")
    void streamsAreIndependentButReproducible() {
        var sampler = AliasSampler.zipf(100, 0.99);
        var a = new SplittableRandom(10);
        var b = new SplittableRandom(10);
        var c = new SplittableRandom(11);

        int agreeAB = 0;
        int agreeAC = 0;
        for (int i = 0; i < 1_000; i++) {
            int x = sampler.sample(a);
            if (x == sampler.sample(b)) {
                agreeAB++;
            }
            if (x == sampler.sample(c)) {
                agreeAC++;
            }
        }

        assertEquals(1_000, agreeAB, "the same seed must reproduce the same stream");
        assertTrue(agreeAC < 400, "independent streams should mostly disagree, but agreed " + agreeAC + " times");
    }
}
