package com.velox.server.loadgen;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipfianSamplerTest {

    @Test
    void everyDrawIsInRange() {
        ZipfianSampler sampler = new ZipfianSampler(100, 1.0, new Random(42));

        for (int i = 0; i < 10_000; i++) {
            int key = sampler.sample();
            assertTrue(key >= 0 && key < 100, "key out of [0, 100): " + key);
        }
    }

    @Test
    void lowKeysAreDrawnFarMoreOftenThanHighOnes() {
        ZipfianSampler sampler = new ZipfianSampler(1000, 1.0, new Random(7));
        int[] counts = new int[1000];

        for (int i = 0; i < 200_000; i++) {
            counts[sampler.sample()]++;
        }

        assertTrue(counts[0] > counts[999] * 50,
                "key 0 (%d draws) should dwarf key 999 (%d draws) under a Zipfian skew"
                        .formatted(counts[0], counts[999]));
    }

    @Test
    void aFlatExponentIsRoughlyUniform() {
        ZipfianSampler sampler = new ZipfianSampler(10, 0.0, new Random(3));
        int[] counts = new int[10];

        for (int i = 0; i < 100_000; i++) {
            counts[sampler.sample()]++;
        }

        for (int count : counts) {
            assertEquals(10_000, count, 1_500, "exponent 0 should draw every key about equally often");
        }
    }
}
