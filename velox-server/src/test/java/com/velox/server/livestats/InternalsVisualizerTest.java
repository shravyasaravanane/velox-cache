package com.velox.server.livestats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalsVisualizerTest {

    @Test
    @DisplayName("LRU view reflects real recency order, MRU first, and evicts at capacity")
    void lruViewTracksRecencyAndEviction() {
        InternalsVisualizer visualizer = new InternalsVisualizer(3);

        visualizer.step(1);
        visualizer.step(2);
        visualizer.step(3);
        assertEquals(java.util.List.of(3L, 2L, 1L), visualizer.describe().lru().mruToLru());

        visualizer.step(1); // touch 1: moves to MRU
        assertEquals(java.util.List.of(1L, 3L, 2L), visualizer.describe().lru().mruToLru());

        visualizer.step(4); // capacity 3: evicts 2 (the LRU entry)
        assertEquals(java.util.List.of(4L, 1L, 3L), visualizer.describe().lru().mruToLru());
    }

    @Test
    @DisplayName("ARC view: a new entry starts in T1, a second use promotes it to T2")
    void arcViewTracksT1AndT2() {
        InternalsVisualizer visualizer = new InternalsVisualizer(4);

        visualizer.step(1);
        assertEquals(java.util.List.of(1L), visualizer.describe().arc().t1());
        assertTrue(visualizer.describe().arc().t2().isEmpty());

        visualizer.step(1); // second reference: promotes to T2
        assertTrue(visualizer.describe().arc().t1().isEmpty());
        assertEquals(java.util.List.of(1L), visualizer.describe().arc().t2());
    }

    @Test
    @DisplayName("ARC view: an evicted never-promoted entry becomes a B1 ghost")
    void arcViewTracksB1Ghosts() {
        InternalsVisualizer visualizer = new InternalsVisualizer(2);

        visualizer.step(1);
        visualizer.step(2);
        visualizer.step(3); // capacity 2: evicts 1 (never promoted) into B1

        assertEquals(java.util.List.of(1L), visualizer.describe().arc().b1());
        assertTrue(visualizer.describe().arc().b2().isEmpty());
    }

    @Test
    @DisplayName("W-TinyLFU view: every new arrival starts in the window")
    void tinyLfuViewTracksWindow() {
        InternalsVisualizer visualizer = new InternalsVisualizer(8);

        visualizer.step(42);

        var view = visualizer.describe().tinyLfu();
        assertTrue(view.window().contains(42L));
        assertTrue(view.probation().isEmpty());
        assertTrue(view.protectedKeys().isEmpty());
    }

    @Test
    @DisplayName("W-TinyLFU view: frequency map reports the sketch estimate for every currently-resident key")
    void tinyLfuViewReportsFrequencies() {
        InternalsVisualizer visualizer = new InternalsVisualizer(8);

        visualizer.step(1);
        visualizer.step(1);
        visualizer.step(1);

        Integer frequency = visualizer.describe().tinyLfu().frequencies().get(1L);
        assertTrue(frequency != null && frequency >= 1, "a thrice-referenced key must have a positive frequency estimate");
    }

    @Test
    @DisplayName("pause stops live traffic from mutating state, but step always applies")
    void pauseBlocksLiveTrafficNotManualSteps() {
        InternalsVisualizer visualizer = new InternalsVisualizer(4);
        visualizer.recordLiveTraffic(1);
        assertEquals(java.util.List.of(1L), visualizer.describe().lru().mruToLru());

        visualizer.pause();
        assertTrue(visualizer.describe().paused());

        visualizer.recordLiveTraffic(2); // must be ignored while paused
        assertEquals(java.util.List.of(1L), visualizer.describe().lru().mruToLru());

        visualizer.step(3); // manual step still applies while paused
        assertEquals(java.util.List.of(3L, 1L), visualizer.describe().lru().mruToLru());

        visualizer.resume();
        assertFalse(visualizer.describe().paused());
        visualizer.recordLiveTraffic(4);
        assertEquals(java.util.List.of(4L, 3L, 1L), visualizer.describe().lru().mruToLru());
    }
}
