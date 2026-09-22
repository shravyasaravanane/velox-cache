package com.velox.core.durability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotterTest {

    @Test
    @DisplayName("a snapshot loads back to exactly the map it was taken from")
    void snapshotRoundTripsExactly(@TempDir Path dir) throws Exception {
        Snapshotter snapshotter = new Snapshotter(dir);
        Map<String, String> data = Map.of("a", "1", "b", "2", "c", "3");

        Path file = snapshotter.snapshot(data);
        Map<String, String> loaded = Snapshotter.load(file);

        assertEquals(data, loaded);
    }

    @Test
    @DisplayName("an empty store snapshots and loads back as an empty map, not an error")
    void emptySnapshotRoundTrips(@TempDir Path dir) throws Exception {
        Snapshotter snapshotter = new Snapshotter(dir);
        Path file = snapshotter.snapshot(Map.of());
        assertTrue(Snapshotter.load(file).isEmpty());
    }

    @Test
    @DisplayName("latest() picks the most recently taken snapshot, not just any one")
    void latestPicksTheMostRecentSnapshot(@TempDir Path dir) throws Exception {
        Snapshotter snapshotter = new Snapshotter(dir);

        snapshotter.snapshot(Map.of("version", "1"));
        Thread.sleep(5); // snapshot files are named by epoch millis; ensure a distinct, later name
        Path newest = snapshotter.snapshot(Map.of("version", "2"));

        var latest = snapshotter.latest();
        assertTrue(latest.isPresent());
        assertEquals(newest, latest.get());
        assertEquals(Map.of("version", "2"), Snapshotter.load(latest.get()));
    }

    @Test
    @DisplayName("with no snapshots yet, latest() is empty rather than throwing")
    void noSnapshotsMeansLatestIsEmpty(@TempDir Path dir) throws Exception {
        Snapshotter snapshotter = new Snapshotter(dir);
        assertTrue(snapshotter.latest().isEmpty());
    }
}
