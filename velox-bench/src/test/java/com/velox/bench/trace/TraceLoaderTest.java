package com.velox.bench.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TraceLoaderTest {

    @Test
    @DisplayName("loadIntegerKeys: parses one integer per line, skipping comments and blank lines")
    void loadIntegerKeysSkipsCommentsAndBlanks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("trace.txt");
        Files.writeString(file, "# a comment line\n10\n20\n\n30\n   \n40\n");

        int[] keys = TraceLoader.loadIntegerKeys(file);

        assertArrayEquals(new int[] {10, 20, 30, 40}, keys);
    }

    @Test
    @DisplayName("loadStringKeysAsDenseIds: the same string always maps to the same id, assigned in first-seen order")
    void loadStringKeysAssignsDenseIdsInFirstSeenOrder(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("trace.txt");
        Files.writeString(file, "cat\ndog\ncat\nbird\ndog\ncat\n");

        int[] keys = TraceLoader.loadStringKeysAsDenseIds(file);

        // cat -> 0 (first seen), dog -> 1, bird -> 2
        assertArrayEquals(new int[] {0, 1, 0, 2, 1, 0}, keys);
    }

    @Test
    @DisplayName("an empty file produces an empty trace")
    void emptyFileProducesEmptyTrace(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.txt");
        Files.writeString(file, "# nothing but comments\n\n");

        assertArrayEquals(new int[0], TraceLoader.loadIntegerKeys(file));
        assertArrayEquals(new int[0], TraceLoader.loadStringKeysAsDenseIds(file));
    }
}
