package com.velox.core.durability;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * M8.2: keeps the write-ahead log bounded. Every entry in {@link WriteAheadLog} is redundant
 * the instant a snapshot has captured the state it produced -- {@link #compact} takes that
 * snapshot, then truncates the WAL to empty, so the log only ever holds the operations since
 * the most recent snapshot, not the entire history back to the store's creation.
 *
 * <p>Older snapshots are deleted once a new one exists: {@link RecoveryManager} only ever needs
 * the latest, and keeping every one forever would defeat the point of compacting anything.
 */
final class Compactor {

    private final Snapshotter snapshotter;

    Compactor(Snapshotter snapshotter) {
        this.snapshotter = snapshotter;
    }

    /**
     * @param data    the current, complete state to snapshot
     * @param walPath the WAL file to truncate once the snapshot is safely on disk
     * @return the new snapshot's path
     */
    Path compact(Map<String, String> data, Path walPath) throws IOException {
        Path newSnapshot = snapshotter.snapshot(data);

        List<Path> all = snapshotter.allSnapshots();
        for (Path old : all) {
            if (!old.equals(newSnapshot)) {
                Files.deleteIfExists(old);
            }
        }

        // DurableStore closes its WriteAheadLog before calling this (see its own compact()) --
        // truncating a path nobody currently has open, then reopening fresh afterward, is a
        // straightforward, obviously-correct sequence. The tempting alternative -- truncate a
        // *still-open* append-mode stream out from under it and rely on append semantics to
        // pick the new end-of-file back up cleanly -- may well work, but "may well" is not a
        // standard this project holds any other file-format code to, so it is not used here.
        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
            channel.truncate(0);
        }

        return newSnapshot;
    }
}
