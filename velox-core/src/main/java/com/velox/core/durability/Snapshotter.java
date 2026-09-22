package com.velox.core.durability;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * M8.2: writes (and reads back) a full point-in-time copy of a {@link DurableStore}'s state, so
 * {@link RecoveryManager} does not have to replay the entire write-ahead log from the beginning
 * of time on every restart -- only the (usually much shorter) tail written since the last
 * snapshot.
 *
 * <h2>File naming carries the ordering</h2>
 *
 * Each snapshot is written to {@code snapshot-<epochMillis>.bin}; {@link #latest} picks the
 * lexicographically (equivalently, numerically, since the timestamp is fixed-free-form decimal)
 * greatest name. No separate index file, no database -- the filesystem directory listing
 * already is the index.
 */
final class Snapshotter {

    private final Path directory;

    Snapshotter(Path directory) {
        this.directory = directory;
    }

    /** Writes {@code data} to a new snapshot file and returns its path. */
    Path snapshot(Map<String, String> data) throws IOException {
        Path file = directory.resolve("snapshot-" + System.currentTimeMillis() + ".bin");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(data.size());
            for (Map.Entry<String, String> entry : data.entrySet()) {
                writeString(out, entry.getKey());
                writeString(out, entry.getValue());
            }
        }
        return file;
    }

    static Map<String, String> load(Path file) throws IOException {
        Map<String, String> result = new HashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String key = readString(in);
                String value = readString(in);
                result.put(key, value);
            }
        }
        return result;
    }

    /** @return every snapshot file currently in the directory, oldest first */
    List<Path> allSnapshots() throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(directory)) {
            return listing
                    .filter(p -> p.getFileName().toString().startsWith("snapshot-"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    Optional<Path> latest() throws IOException {
        List<Path> all = allSnapshots();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
