package com.velox.core.durability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableStoreTest {

    @Test
    @DisplayName("put/invalidate/clear are visible immediately through get()")
    void basicOperationsAreVisibleImmediately() throws Exception {
        try (var store = openStore(Files.createTempDirectory("velox-durable-"))) {
            store.put("a", "1", -1);
            assertEquals("1", store.get("a"));

            store.invalidate("a");
            assertNull(store.get("a"));

            store.put("b", "2", -1);
            store.clear();
            assertNull(store.get("b"));
        }
    }

    @Test
    @DisplayName("closing and reopening on the same directory recovers exactly the prior state (WAL replay, no snapshot yet)")
    void reopeningRecoversStateFromTheWalAlone() throws Exception {
        Path dir = Files.createTempDirectory("velox-durable-");

        try (var store = openStore(dir)) {
            store.put("a", "1", -1);
            store.put("b", "2", -1);
            store.invalidate("a");
        }

        try (var reopened = openStore(dir)) {
            assertNull(reopened.get("a"));
            assertEquals("2", reopened.get("b"));
            assertEquals(1, reopened.size());
            assertFalse(reopened.lastRecoveryFoundCorruption());
        }
    }

    @Test
    @DisplayName("an entry whose TTL elapsed while the store was closed does not come back on recovery")
    void expiredEntriesAreNotResurrectedOnRecovery() throws Exception {
        Path dir = Files.createTempDirectory("velox-durable-");

        try (var store = openStore(dir)) {
            store.put("short-lived", "value", 50); // 50ms TTL
            store.put("permanent", "value", -1);
        }
        Thread.sleep(150); // let the short-lived entry's TTL elapse while "the process is down"

        try (var reopened = openStore(dir)) {
            assertNull(reopened.get("short-lived"), "an entry expired during the outage must not be resurrected");
            assertEquals("value", reopened.get("permanent"));
        }
    }

    @Test
    @DisplayName("compaction snapshots the state and truncates the WAL, and recovery afterward still works")
    void compactionShrinksTheWalAndRecoveryStillWorksAfterward() throws Exception {
        Path dir = Files.createTempDirectory("velox-durable-");
        Path walPath = dir.resolve("wal.log");

        try (var store = openStoreWithCompactionEvery(dir, 3)) {
            store.put("a", "1", -1);
            store.put("b", "2", -1);
            store.put("c", "3", -1); // the 3rd op: triggers compaction
        }

        // After compaction, the WAL holds nothing but whatever happened after it -- here,
        // nothing -- so it must be small; the state must have moved into a snapshot file.
        assertTrue(Files.size(walPath) < 50, "WAL should be (near-)empty right after compaction");

        try (var reopened = openStore(dir)) {
            assertEquals("1", reopened.get("a"));
            assertEquals("2", reopened.get("b"));
            assertEquals("3", reopened.get("c"));
        }
    }

    /**
     * M8.3: the crash-recovery test. A literal {@code kill -9} mid-fsync is not something an
     * automated test can trigger deterministically -- so, following the same technique real
     * WAL/database test suites use (SQLite's and LevelDB's own crash tests both work this way),
     * this directly produces the exact <i>physical</i> artifact a crash produces: a log file
     * ending in a truncated, unverifiable record. If recovery handles that file correctly, it
     * handles a real crash correctly, because the file is indistinguishable from one a real
     * crash left behind.
     */
    @Test
    @DisplayName("M8.3: a WAL torn by a simulated crash recovers everything valid before the tear, discards exactly the torn record, and never throws")
    void crashRecoveryDiscardsExactlyTheTornTailAndNothingElse() throws Exception {
        Path dir = Files.createTempDirectory("velox-durable-");
        Path walPath = dir.resolve("wal.log");

        try (var store = openStore(dir)) {
            store.put("safe-1", "before the crash", -1);
            store.put("safe-2", "also before the crash", -1);
        }
        long cleanLength = Files.size(walPath);

        // Simulate the crash: a length prefix announcing a record, then only PART of the bytes
        // that record was supposed to contain -- exactly what a process dying mid-write (or
        // mid-fsync) leaves behind. No CRC follows, because the crash happened before one could
        // be written.
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            raf.seek(cleanLength);
            raf.writeInt(1000); // announces a 1000-byte payload
            raf.write(new byte[]{1, 2, 3, 4, 5}); // only 5 bytes actually arrive -- then nothing
        }
        long tornLength = Files.size(walPath);
        assertTrue(tornLength > cleanLength, "the file must actually be longer -- the torn bytes were really appended");

        // Recovery happens in the constructor. This must not throw.
        try (var recovered = openStore(dir)) {
            assertEquals("before the crash", recovered.get("safe-1"));
            assertEquals("also before the crash", recovered.get("safe-2"));
            assertEquals(2, recovered.size(), "only the two clean records may survive -- nothing from the torn one");
            assertTrue(recovered.lastRecoveryFoundCorruption(), "recovery must report that it found and discarded a torn tail");

            // The torn bytes must have been physically truncated away, not just skipped in memory --
            // otherwise the next append would leave live data sitting after dead garbage in the file.
            assertEquals(cleanLength, Files.size(walPath), "the WAL file itself must be truncated back to the last valid byte");

            // The store must still be fully usable after recovering from corruption.
            recovered.put("safe-3", "written after recovery", -1);
        }

        // And a SECOND reopen must show a completely clean log: the post-recovery write landed
        // correctly, and nothing about the earlier corruption lingers.
        try (var reopenedAgain = openStore(dir)) {
            assertEquals("before the crash", reopenedAgain.get("safe-1"));
            assertEquals("also before the crash", reopenedAgain.get("safe-2"));
            assertEquals("written after recovery", reopenedAgain.get("safe-3"));
            assertEquals(3, reopenedAgain.size());
            assertFalse(reopenedAgain.lastRecoveryFoundCorruption(), "the log is clean now -- there is nothing left to report");
        }
    }

    @Test
    @DisplayName("a WAL that ends with a clean, complete final record reports no corruption at all")
    void aCleanlyClosedLogReportsNoCorruption() throws Exception {
        Path dir = Files.createTempDirectory("velox-durable-");
        try (var store = openStore(dir)) {
            store.put("a", "1", -1);
        }
        try (var reopened = openStore(dir)) {
            assertFalse(reopened.lastRecoveryFoundCorruption());
        }
    }

    private static DurableStore openStore(Path dir) throws Exception {
        return new DurableStore(dir, WriteAheadLog.FsyncPolicy.ALWAYS, Integer.MAX_VALUE);
    }

    private static DurableStore openStoreWithCompactionEvery(Path dir, int n) throws Exception {
        return new DurableStore(dir, WriteAheadLog.FsyncPolicy.ALWAYS, n);
    }
}
