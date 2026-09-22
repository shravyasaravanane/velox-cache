package com.velox.bench.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadsTest {

    @Test
    @DisplayName("uniform: every key is in range, and the same seed reproduces the same trace")
    void uniformStaysInRangeAndIsReproducible() {
        var workload = Workloads.uniform(100);

        int[] trace = workload.generate(1, 10_000);

        for (int key : trace) {
            assertTrue(key >= 0 && key < 100, "key " + key + " out of range");
        }
        assertArrayEquals(trace, workload.generate(1, 10_000), "the same seed must reproduce the same trace");
    }

    @Test
    @DisplayName("uniform: roughly equal coverage across the key space")
    void uniformCoversAllKeysRoughlyEqually() {
        int keySpace = 20;
        var trace = Workloads.uniform(keySpace).generate(2, 200_000);

        long[] counts = new long[keySpace];
        for (int key : trace) {
            counts[key]++;
        }
        double expected = trace.length / (double) keySpace;
        for (long count : counts) {
            assertTrue(Math.abs(count - expected) < expected * 0.15,
                    "count " + count + " too far from the expected " + expected);
        }
    }

    @Test
    @DisplayName("zipfian: rank 0 is drawn far more often than rank 1, which beats a middling rank")
    void zipfianIsSkewedTowardsLowRanks() {
        var trace = Workloads.zipfian(1_000, 0.99).generate(3, 500_000);

        long[] counts = new long[1_000];
        for (int key : trace) {
            counts[key]++;
        }
        assertTrue(counts[0] > counts[1], "rank 0 must beat rank 1");
        assertTrue(counts[1] > counts[500], "rank 1 must beat a middling rank");
        for (int key : trace) {
            assertTrue(key >= 0 && key < 1_000, "key " + key + " out of range");
        }
    }

    @Test
    @DisplayName("scan: a plain sequential walk that wraps once it exhausts the key space")
    void scanWalksSequentiallyAndWraps() {
        int[] trace = Workloads.scan(5).generate(0, 12);

        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 1}, trace);
    }

    @Test
    @DisplayName("loop: the same sequential math as scan, under its own name")
    void loopMatchesScanMath() {
        assertArrayEquals(Workloads.scan(4).generate(0, 20), Workloads.loop(4).generate(0, 20));
        assertEquals("loop(4)", Workloads.loop(4).toString());
    }

    @Test
    @DisplayName("hot-set-shift: every request in a period lands in that period's window, and windows move")
    void hotSetShiftWindowsMoveEachPeriod() {
        int keySpace = 100;
        int hotSize = 10;
        int shiftPeriod = 1_000;
        int[] trace = Workloads.hotSetShift(keySpace, hotSize, shiftPeriod).generate(4, 5_000);

        for (int i = 0; i < trace.length; i++) {
            int period = i / shiftPeriod;
            int windowStart = (period * hotSize) % keySpace;
            int offset = Math.floorMod(trace[i] - windowStart, keySpace);
            assertTrue(offset < hotSize, "request " + i + " (key " + trace[i] + ") fell outside period " + period
                    + "'s window starting at " + windowStart);
        }

        // Different periods must actually use different windows, or this would be indistinguishable from uniform.
        int windowOfPeriod0 = 0;
        int windowOfPeriod1 = hotSize % keySpace;
        assertTrue(windowOfPeriod0 != windowOfPeriod1 || hotSize >= keySpace);
    }

    @Test
    @DisplayName("two-pool: every key falls in exactly one pool, and the split roughly matches hotShare")
    void twoPoolSplitsByShare() {
        int hotSize = 5;
        int coldSize = 500;
        double hotShare = 0.8;
        int[] trace = Workloads.twoPool(hotSize, coldSize, hotShare).generate(5, 100_000);

        long hotHits = 0;
        for (int key : trace) {
            assertTrue(key >= 0 && key < hotSize + coldSize, "key " + key + " out of range");
            if (key < hotSize) {
                hotHits++;
            }
        }
        double observedShare = (double) hotHits / trace.length;
        assertTrue(Math.abs(observedShare - hotShare) < 0.02,
                "observed hot share " + observedShare + " too far from requested " + hotShare);
    }

    @Test
    @DisplayName("scan-flood: hot requests stay in the hot range, burst requests walk the disjoint cold range in order")
    void scanFloodAlternatesHotAndColdBursts() {
        int hotKeySpace = 20;
        int coldKeySpace = 1_000;
        int burstEvery = 50;
        int burstLength = 30;
        int[] trace = Workloads.scanFlood(hotKeySpace, 0.99, coldKeySpace, burstEvery, burstLength)
                .generate(6, 500);

        int coldCursor = 0;
        int i = 0;
        while (i < trace.length) {
            int hotChunk = Math.min(burstEvery, trace.length - i);
            for (int end = i + hotChunk; i < end; i++) {
                assertTrue(trace[i] >= 0 && trace[i] < hotKeySpace, "expected a hot key at position " + i);
            }
            int scanChunk = Math.min(burstLength, trace.length - i);
            for (int end = i + scanChunk; i < end; i++, coldCursor++) {
                assertEquals(hotKeySpace + (coldCursor % coldKeySpace), trace[i],
                        "the scan burst must walk the cold range in order, position " + i);
            }
        }
    }

    @Test
    @DisplayName("bad arguments are rejected")
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> Workloads.uniform(0));
        assertThrows(IllegalArgumentException.class, () -> Workloads.zipfian(10, 0));
        assertThrows(IllegalArgumentException.class, () -> Workloads.scan(0));
        assertThrows(IllegalArgumentException.class, () -> Workloads.loop(0));
        assertThrows(IllegalArgumentException.class, () -> Workloads.hotSetShift(10, 20, 5));
        assertThrows(IllegalArgumentException.class, () -> Workloads.twoPool(5, 5, 1.5));
        assertThrows(IllegalArgumentException.class, () -> Workloads.scanFlood(0, 0.99, 10, 5, 5));
    }
}
