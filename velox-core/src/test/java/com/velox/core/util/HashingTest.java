package com.velox.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Hashing}.
 */
class HashingTest {

    @Test
    @DisplayName("nextPowerOfTwo rounds up correctly, including the edges")
    void nextPowerOfTwoRoundsUp() {
        assertEquals(1, Hashing.nextPowerOfTwo(0));
        assertEquals(1, Hashing.nextPowerOfTwo(1));
        assertEquals(2, Hashing.nextPowerOfTwo(2));
        assertEquals(4, Hashing.nextPowerOfTwo(3));
        assertEquals(4, Hashing.nextPowerOfTwo(4));
        assertEquals(8, Hashing.nextPowerOfTwo(5));
        assertEquals(16, Hashing.nextPowerOfTwo(16));
        assertEquals(1024, Hashing.nextPowerOfTwo(1000));
        assertEquals(1, Hashing.nextPowerOfTwo(-7), "negatives collapse to the minimum");
    }

    @Test
    @DisplayName("every result of nextPowerOfTwo really is a power of two")
    void nextPowerOfTwoAlwaysReturnsPowerOfTwo() {
        // The map relies on this: `hash & (capacity - 1)` is only equivalent
        // to `hash % capacity` when capacity is a power of two.
        for (int i = 0; i < 5000; i++) {
            int result = Hashing.nextPowerOfTwo(i);
            assertEquals(1, Integer.bitCount(result), result + " is not a power of two");
            assertTrue(result >= Math.max(1, i), result + " is smaller than the requested " + i);
        }
    }

    @Test
    @DisplayName("mixing the same input always gives the same output")
    void mixIsDeterministic() {
        // A hash must be stable, or an entry could never be found again.
        assertEquals(Hashing.mix(12345), Hashing.mix(12345));
        assertEquals(Hashing.spread("hello"), Hashing.spread("hello"));
    }

    @Test
    @DisplayName("spreading rescues the clustering that raw hash codes cause")
    void spreadFixesClusteredHashCodes() {
        // This test demonstrates the exact failure Hashing exists to prevent.
        //
        // Integer.hashCode() returns the integer itself. So IDs that are
        // multiples of 16 have all-zero low bits, and `hash & 15` gives 0
        // every single time. Every key piles into slot 0, the probe chain
        // grows to length n, and the "O(1)" map becomes an O(n) scan.
        //
        // Sequential and strided IDs are extremely common in real systems --
        // database primary keys, timestamps, pointers -- so this is a
        // practical hazard, not a contrived one.
        int slots = 16;
        int mask = slots - 1;
        int keys = 256;

        int[] rawBuckets = new int[slots];
        int[] spreadBuckets = new int[slots];

        for (int i = 0; i < keys; i++) {
            Integer id = i * 16;                       // stride of 16
            rawBuckets[id.hashCode() & mask]++;
            spreadBuckets[Hashing.spread(id) & mask]++;
        }

        // Without spreading: total collapse into a single slot.
        assertEquals(keys, rawBuckets[0],
                "raw hash codes should all land in slot 0 -- that is the problem");

        // With spreading: roughly even. Perfect would be 16 per slot; we
        // allow a generous band because hashing is random, not uniform.
        int perfect = keys / slots;
        for (int slot = 0; slot < slots; slot++) {
            assertTrue(spreadBuckets[slot] > 0,
                    "slot " + slot + " got nothing -- spreading is not working");
            assertTrue(spreadBuckets[slot] < perfect * 3,
                    "slot " + slot + " got " + spreadBuckets[slot]
                            + ", far above the expected " + perfect);
        }
    }

    @Test
    @DisplayName("flipping one input bit changes about half the output bits")
    void mixHasTheAvalancheProperty() {
        // "Avalanche" is the property that makes a mixer useful: a tiny input
        // change must scatter unpredictably across the whole output. If it did
        // not, similar keys would still land in similar slots after mixing.
        int samples = 2000;
        long totalFlipped = 0;

        for (int i = 0; i < samples; i++) {
            int before = Hashing.mix(i);
            int after = Hashing.mix(i ^ 1);            // flip the lowest bit only
            totalFlipped += Integer.bitCount(before ^ after);
        }

        double averageFlipped = (double) totalFlipped / samples;

        // Ideal is 16 of 32 bits. Anything in 13-19 shows genuine avalanche.
        assertTrue(averageFlipped > 13 && averageFlipped < 19,
                "expected about 16 of 32 bits to flip, got " + averageFlipped);
    }
}
