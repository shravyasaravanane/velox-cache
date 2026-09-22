package com.velox.core.durability;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * M8.2: reconstructs a {@link DurableStore}'s state on startup -- the latest {@link Snapshotter}
 * snapshot, if any, plus every valid {@link WalRecord} on top of it from the write-ahead log
 * written since.
 *
 * <h2>Why TTL is re-checked here, not just trusted</h2>
 *
 * A {@code Put} record's TTL was measured from its own {@code timestampMillis} -- the moment it
 * was written, which could have been minutes, hours, or (after a crash and a slow restart)
 * conceivably longer ago. Blindly restoring it would let an entry that expired while the process
 * was down come back to life on recovery, silently wrong. {@link #recover} checks each put's
 * deadline against the current clock and drops it if it has already passed, so recovery never
 * resurrects data that should already be gone.
 */
final class RecoveryManager {

    record Result(Map<String, String> entries, long walValidByteLength, boolean walTailWasCorrupted) {
    }

    private final Path directory;
    private final Snapshotter snapshotter;

    RecoveryManager(Path directory, Snapshotter snapshotter) {
        this.directory = directory;
        this.snapshotter = snapshotter;
    }

    Result recover() throws IOException {
        Map<String, String> state = new HashMap<>();
        var latestSnapshot = snapshotter.latest();
        if (latestSnapshot.isPresent()) {
            state.putAll(Snapshotter.load(latestSnapshot.get()));
        }

        Path walPath = directory.resolve(DurableStore.WAL_FILE_NAME);
        long now = System.currentTimeMillis();

        WalReader.ReadResult walResult = WalReader.read(walPath, record -> {
            switch (record) {
                case WalRecord.Put put -> {
                    boolean expired = put.ttlMillis() >= 0 && put.timestampMillis() + put.ttlMillis() <= now;
                    if (expired) {
                        state.remove(put.key());
                    } else {
                        state.put(put.key(), put.value());
                    }
                }
                case WalRecord.Invalidate invalidate -> state.remove(invalidate.key());
                case WalRecord.Clear ignored -> state.clear();
            }
        });

        return new Result(state, walResult.validByteLength(), walResult.truncatedTailDetected());
    }
}
