package com.velox.core.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountMinSketchTest {

    @Test
    @DisplayName("estimate is never below the true count, however the hashes happen to land")
    void neverUnderestimates() {
        // Wide enough that collisions among 200 keys are rare, but the assertion below holds
        // regardless: it is a mathematical guarantee of the structure, not a statistical one.
        var sketch = new CountMinSketch(4096, 4, 0);
        var random = new Random(42);
        Map<String, Integer> trueCounts = new HashMap<>();

        for (int i = 0; i < 200; i++) {
            String key = "k" + i;
            int count = 1 + random.nextInt(10);
            trueCounts.put(key, count);
            for (int j = 0; j < count; j++) {
                sketch.increment(key);
            }
        }

        for (var entry : trueCounts.entrySet()) {
            int estimate = sketch.estimate(entry.getKey());
            assertTrue(estimate >= entry.getValue(),
                    entry.getKey() + ": estimate " + estimate + " is below the true count " + entry.getValue());
        }
    }

    @Test
    @DisplayName("a key never incremented reads back as zero, in an otherwise-empty region")
    void neverSeenKeyEstimatesZero() {
        var sketch = new CountMinSketch(4096, 4, 0);
        sketch.increment("A");

        assertEquals(0, sketch.estimate("this-key-was-never-touched"));
    }

    @Test
    @DisplayName("a counter saturates at 15 and never overflows")
    void saturatesAtFifteen() {
        var sketch = new CountMinSketch(64, 2, 0);
        for (int i = 0; i < 30; i++) {
            sketch.increment("A");
        }

        assertEquals(15, sketch.estimate("A"));
    }

    @Test
    @DisplayName("periodic halving: crossing resetAtCount halves every counter immediately")
    void periodicHalvingResetsCounts() {
        // depth=1 makes this fully deterministic: one row, no "minimum across rows" to reason about.
        var sketch = new CountMinSketch(64, 1, 4);
        sketch.increment("A");
        sketch.increment("A");
        sketch.increment("A");
        assertEquals(3, sketch.estimate("A"));

        sketch.increment("A");          // the 4th increment: count becomes 4, which also crosses resetAtCount

        assertEquals(2, sketch.estimate("A"), "4, halved, is 2");
    }

    @Test
    @DisplayName("resetAtCount of 0 disables aging")
    void zeroResetAtCountDisablesAging() {
        var sketch = new CountMinSketch(64, 1, 0);
        for (int i = 0; i < 20; i++) {
            sketch.increment("A");
        }

        assertEquals(15, sketch.estimate("A"), "no halving ever happened; the counter simply saturated");
    }

    @Test
    @DisplayName("clear forgets every count")
    void clearForgetsEverything() {
        var sketch = new CountMinSketch(64, 2, 0);
        sketch.increment("A");
        sketch.increment("A");

        sketch.clear();

        assertEquals(0, sketch.estimate("A"));
    }

    @Test
    @DisplayName("the convenience constructor produces a usable sketch")
    void convenienceConstructorWorks() {
        var sketch = new CountMinSketch(1000);

        sketch.increment("A");

        assertTrue(sketch.estimate("A") >= 1);
    }

    @Test
    @DisplayName("constructor arguments are validated")
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(0, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(64, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(64, 4, -1));
    }
}
