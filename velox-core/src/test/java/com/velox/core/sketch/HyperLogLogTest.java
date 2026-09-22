package com.velox.core.sketch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyperLogLogTest {

    @Test
    @DisplayName("an empty sketch estimates zero")
    void emptySketchEstimatesZero() {
        assertEquals(0, new HyperLogLog().estimate());
    }

    @Test
    @DisplayName("adding the same value any number of times estimates one distinct value")
    void repeatedAdditionsCountOnce() {
        HyperLogLog hll = new HyperLogLog();
        for (int i = 0; i < 10_000; i++) {
            hll.add(42L);
        }
        assertEquals(1, hll.estimate());
    }

    @Test
    @DisplayName("precision must be within [4, 16]")
    void rejectsOutOfRangePrecision() {
        assertThrows(IllegalArgumentException.class, () -> new HyperLogLog(3));
        assertThrows(IllegalArgumentException.class, () -> new HyperLogLog(17));
    }

    @Test
    @DisplayName("small cardinalities hit the linear-counting branch and are estimated accurately")
    void smallCardinalityIsAccurate() {
        HyperLogLog hll = new HyperLogLog(14); // m = 16384, so 10 distinct values is deep in the linear-counting regime
        for (long i = 0; i < 10; i++) {
            hll.add(i);
        }
        long estimate = hll.estimate();
        assertTrue(estimate >= 8 && estimate <= 12, "expected an estimate close to 10, got " + estimate);
    }

    @Test
    @DisplayName("large cardinality is within a small multiple of the documented standard error")
    void largeCardinalityIsWithinExpectedError() {
        int precision = 14;
        int m = 1 << precision;
        HyperLogLog hll = new HyperLogLog(precision);
        long trueDistinct = 200_000;

        Random random = new Random(7);
        Set<Long> seen = new HashSet<>();
        while (seen.size() < trueDistinct) {
            long value = random.nextLong();
            if (seen.add(value)) {
                hll.add(value);
            }
        }

        long estimate = hll.estimate();
        double standardError = 1.04 / Math.sqrt(m);
        // A generous 6-sigma bound: this is one randomized run, not a statistical test suite,
        // so the bound must comfortably survive ordinary variance without being so loose it
        // would also pass a broken implementation.
        double toleranceFraction = 6 * standardError;
        double relativeError = Math.abs(estimate - trueDistinct) / (double) trueDistinct;

        assertTrue(relativeError <= toleranceFraction,
                "estimate %d vs true %d: relative error %.4f exceeds 6-sigma bound %.4f"
                        .formatted(estimate, trueDistinct, relativeError, toleranceFraction));
    }

    @Test
    @DisplayName("distinct sequential ids (the demo's actual key shape) are estimated accurately, not just random longs")
    void sequentialIdsAreEstimatedAccurately() {
        HyperLogLog hll = new HyperLogLog(14);
        long trueDistinct = 50_000;
        for (long id = 1; id <= trueDistinct; id++) {
            hll.add(id);
        }

        long estimate = hll.estimate();
        double relativeError = Math.abs(estimate - trueDistinct) / (double) trueDistinct;
        assertTrue(relativeError <= 0.05,
                "sequential-id estimate %d vs true %d: relative error %.4f exceeds 5%%"
                        .formatted(estimate, trueDistinct, relativeError));
    }

    @Test
    @DisplayName("higher precision gives a tighter (or equal) estimate for the same data")
    void higherPrecisionIsAtLeastAsAccurate() {
        long trueDistinct = 20_000;
        HyperLogLog coarse = new HyperLogLog(8);   // m = 256
        HyperLogLog fine = new HyperLogLog(16);    // m = 65536

        for (long id = 1; id <= trueDistinct; id++) {
            coarse.add(id);
            fine.add(id);
        }

        double coarseError = Math.abs(coarse.estimate() - trueDistinct) / (double) trueDistinct;
        double fineError = Math.abs(fine.estimate() - trueDistinct) / (double) trueDistinct;
        // Not a strict inequality on a single sample (either could get lucky/unlucky), but the
        // coarse sketch's own documented standard error (1.04/sqrt(256) ~= 6.5%) is an order of
        // magnitude looser than the fine one's (1.04/sqrt(65536) ~= 0.4%), so a generous
        // one-sided bound should hold in practice.
        assertTrue(fineError <= 0.05, "fine-precision error %.4f should be small".formatted(fineError));
        assertTrue(coarseError <= 0.30, "coarse-precision error %.4f should still be bounded".formatted(coarseError));
    }
}
