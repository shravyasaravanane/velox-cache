package com.velox.server.livestats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyRingBufferTest {

    @Test
    void percentileIsZeroWithNoSamples() {
        LatencyRingBuffer buffer = new LatencyRingBuffer(8);

        assertEquals(0.0, buffer.percentileMillis(0.50));
        assertEquals(0.0, buffer.percentileMillis(0.99));
    }

    @Test
    void percentilesAreCorrectBeforeWrapping() {
        LatencyRingBuffer buffer = new LatencyRingBuffer(100);
        for (int ms = 1; ms <= 100; ms++) {
            buffer.record(ms * 1_000_000L);
        }

        // Nearest-rank on a 0-indexed, 100-element sorted array [1..100]: index = floor(f*100),
        // so p50 -> index 50 -> the 51st-smallest value (50 values are strictly below it).
        assertEquals(51.0, buffer.percentileMillis(0.50), 0.001);
        assertEquals(100.0, buffer.percentileMillis(0.99), 0.001);
        assertEquals(100.0, buffer.percentileMillis(1.0), 0.001);
    }

    @Test
    void bufferNeverGrowsPastItsCapacityOnceWrapped() {
        LatencyRingBuffer buffer = new LatencyRingBuffer(10);
        // 1000 writes to a 10-slot buffer land every slot back on 5ms (1000 is a multiple of
        // 10), then one more write (index 1000, slot 0) overwrites exactly one slot with the
        // 500ms outlier -- so the last 10 samples are nine 5ms values plus that one 500ms value.
        for (int i = 0; i < 1000; i++) {
            buffer.record(5_000_000L); // 5 ms
        }
        buffer.record(500_000_000L); // 500 ms

        assertEquals(5.0, buffer.percentileMillis(0.50), 0.001, "p50 of nine 5ms + one 500ms is 5ms");
        assertEquals(500.0, buffer.percentileMillis(0.99), 0.001,
                "p99 of ten samples is the single outlier -- older 5ms samples beyond the last 10 must not count");
    }
}
