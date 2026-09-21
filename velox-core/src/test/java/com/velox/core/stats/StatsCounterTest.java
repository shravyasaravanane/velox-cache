package com.velox.core.stats;

import com.velox.core.RemovalCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link StatsCounter}, {@link CacheStats} and {@link RemovalCause}.
 */
class StatsCounterTest {

    @Test
    @DisplayName("each counter records independently")
    void countersAreIndependent() {
        var counter = new StatsCounter();

        counter.recordHit();
        counter.recordHit();
        counter.recordMiss();
        counter.recordEviction();
        counter.recordLoad();
        counter.recordRejection();

        CacheStats stats = counter.snapshot();
        assertEquals(2, stats.hitCount());
        assertEquals(1, stats.missCount());
        assertEquals(1, stats.evictionCount());
        assertEquals(1, stats.loadCount());
        assertEquals(1, stats.rejectionCount());
        assertEquals(3, stats.requestCount());
    }

    @Test
    @DisplayName("reset returns every counter to zero")
    void resetClearsEverything() {
        var counter = new StatsCounter();
        counter.recordHit();
        counter.recordMiss();
        counter.recordEviction();
        counter.recordLoad();
        counter.recordRejection();

        counter.reset();

        assertEquals(CacheStats.EMPTY, counter.snapshot());
    }

    @Test
    @DisplayName("a snapshot is frozen at the moment it is taken")
    void snapshotsDoNotChangeAfterwards() {
        var counter = new StatsCounter();
        counter.recordHit();

        CacheStats snapshot = counter.snapshot();
        counter.recordHit();
        counter.recordHit();

        assertEquals(1, snapshot.hitCount(), "the snapshot moved after it was taken");
        assertEquals(3, counter.snapshot().hitCount());
    }

    @Test
    @DisplayName("a cache that was never asked anything reports a hit rate of 1.0")
    void emptyStatsAvoidDivisionByZero() {
        assertEquals(1.0, CacheStats.EMPTY.hitRate(), 1e-9);
        assertEquals(0.0, CacheStats.EMPTY.missRate(), 1e-9);
        assertEquals(0, CacheStats.EMPTY.requestCount());
    }

    @Test
    @DisplayName("minus gives the activity between two snapshots")
    void minusReportsTheDelta() {
        // This is how the Tier 6 dashboard charts a LIVE hit rate: counters
        // only ever climb, so a raw snapshot describes the cache's entire
        // lifetime. Subtracting the previous snapshot gives the last second.
        var earlier = new CacheStats(100, 20, 5, 3, 1);
        var later = new CacheStats(150, 25, 8, 4, 1);

        CacheStats delta = later.minus(earlier);

        assertEquals(50, delta.hitCount());
        assertEquals(5, delta.missCount());
        assertEquals(3, delta.evictionCount());
        assertEquals(1, delta.loadCount());
        assertEquals(0, delta.rejectionCount());
    }

    @Test
    @DisplayName("minus never returns a negative count if snapshots arrive out of order")
    void minusClampsAtZero() {
        // Snapshots can arrive out of order once several threads report
        // metrics. A negative "hits per second" on a dashboard is nonsense,
        // so we clamp instead of propagating it.
        var later = new CacheStats(10, 10, 10, 10, 10);
        var earlier = new CacheStats(100, 100, 100, 100, 100);

        CacheStats delta = later.minus(earlier);

        assertEquals(0, delta.hitCount());
        assertEquals(0, delta.missCount());
        assertEquals(0, delta.evictionCount());
    }

    @Test
    @DisplayName("toString reports the hit rate as a readable percentage")
    void toStringIsReadable() {
        var stats = new CacheStats(75, 25, 10, 5, 2);

        String text = stats.toString();

        assertTrue(text.contains("hits=75"), text);
        assertTrue(text.contains("75.00%"), "expected a formatted hit rate in: " + text);
    }

    @Test
    @DisplayName("only capacity-driven removals count as automatic")
    void removalCauseDistinguishesAutomaticRemovals() {
        // The distinction matters: a rising SIZE count means memory pressure
        // and a bigger cache would help. A rising EXPLICIT count just means
        // callers are deleting things, which says nothing about cache health.
        assertTrue(RemovalCause.SIZE.wasAutomatic());
        assertTrue(RemovalCause.EXPIRED.wasAutomatic());
        assertFalse(RemovalCause.EXPLICIT.wasAutomatic());
        assertFalse(RemovalCause.REPLACED.wasAutomatic());

        assertEquals(4, RemovalCause.values().length);
        assertEquals(RemovalCause.SIZE, RemovalCause.valueOf("SIZE"));
    }
}
