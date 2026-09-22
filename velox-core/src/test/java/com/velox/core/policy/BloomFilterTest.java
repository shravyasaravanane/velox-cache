package com.velox.core.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterTest {

    @Test
    @DisplayName("never a false negative: every added key still reads as present")
    void neverAFalseNegative() {
        var filter = new BloomFilter(1000, 5);
        for (int i = 0; i < 1000; i++) {
            filter.add("k" + i);
        }

        for (int i = 0; i < 1000; i++) {
            assertTrue(filter.mightContain("k" + i), "k" + i + " was added and must never read back as absent");
        }
    }

    @Test
    @DisplayName("a key never added is (almost always) reported absent")
    void falsePositiveRateStaysLow() {
        var filter = new BloomFilter(1000, 5);
        for (int i = 0; i < 1000; i++) {
            filter.add("added" + i);
        }

        int falsePositives = 0;
        int trials = 10_000;
        for (int i = 0; i < trials; i++) {
            if (filter.mightContain("never-added" + i)) {
                falsePositives++;
            }
        }

        // Sized at 8 bits/key with 5 hash functions, textbook false-positive rate is
        // around 2%. A generous ceiling keeps this deterministic test far from flaky.
        double rate = (double) falsePositives / trials;
        assertTrue(rate < 0.10, "false-positive rate " + rate + " is far above what this sizing should produce");
    }

    @Test
    @DisplayName("seenBefore: false on a key's first appearance, true on its second")
    void seenBeforeTellsFirstFromSecond() {
        var filter = new BloomFilter(100, 4);

        assertFalse(filter.seenBefore("A"), "A's first appearance");
        assertTrue(filter.seenBefore("A"), "A's second appearance");
        assertTrue(filter.seenBefore("A"), "and every appearance after that");
    }

    @Test
    @DisplayName("seenBefore records the key exactly as add() would")
    void seenBeforeIsConsistentWithMightContain() {
        var filter = new BloomFilter(100, 4);

        filter.seenBefore("A");

        assertTrue(filter.mightContain("A"), "the key's bits were set on its first appearance");
    }

    @Test
    @DisplayName("clear forgets every key")
    void clearForgetsEverything() {
        var filter = new BloomFilter(100, 4);
        for (int i = 0; i < 50; i++) {
            filter.add("k" + i);
        }

        filter.clear();

        // Guaranteed, not probabilistic: clear() zeroes every bit, and mightContain needs
        // at least one set bit to ever return true.
        for (int i = 0; i < 50; i++) {
            assertFalse(filter.mightContain("k" + i));
        }
    }

    @Test
    @DisplayName("constructor arguments are validated")
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 4));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(100, 0));
    }

    @Test
    @DisplayName("more hash functions generally lowers the false-positive rate, up to a point")
    void moreHashFunctionsLowersFalsePositives() {
        // A single, seeded comparison -- illustrative of the trade the class documents,
        // not a proof, since the "up to a point" part depends on load factor.
        var random = new Random(7);
        int inserted = 2000;
        var sparse = new BloomFilter(inserted, 2);
        var denser = new BloomFilter(inserted, 6);
        for (int i = 0; i < inserted; i++) {
            String key = "k" + i;
            sparse.add(key);
            denser.add(key);
        }

        int sparseFalsePositives = 0;
        int denserFalsePositives = 0;
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            String key = "probe" + random.nextInt();
            if (sparse.mightContain(key)) {
                sparseFalsePositives++;
            }
            if (denser.mightContain(key)) {
                denserFalsePositives++;
            }
        }

        assertTrue(denserFalsePositives <= sparseFalsePositives,
                "6 hash functions (" + denserFalsePositives + ") should not do worse than 2 (" + sparseFalsePositives
                        + ") at this load factor");
    }
}
