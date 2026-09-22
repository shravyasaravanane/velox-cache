package com.velox.core.durability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteAheadLogAndReaderTest {

    @Test
    @DisplayName("a fresh log with no writes reads back as empty, not an error")
    void emptyLogReadsAsEmpty(@TempDir Path dir) throws Exception {
        Path walPath = dir.resolve("wal.log");
        try (var wal = new WriteAheadLog(walPath, WriteAheadLog.FsyncPolicy.ALWAYS)) {
            // no writes
        }

        List<WalRecord> collected = new ArrayList<>();
        var result = WalReader.read(walPath, collected::add);

        assertTrue(collected.isEmpty());
        assertFalse(result.truncatedTailDetected());
    }

    @Test
    @DisplayName("put/invalidate/clear round-trip through the log in the order they were written")
    void recordsRoundTripInOrder(@TempDir Path dir) throws Exception {
        Path walPath = dir.resolve("wal.log");
        try (var wal = new WriteAheadLog(walPath, WriteAheadLog.FsyncPolicy.ALWAYS)) {
            wal.appendPut("a", "1", -1);
            wal.appendPut("b", "2", 60_000);
            wal.appendInvalidate("a");
            wal.appendClear();
        }

        List<WalRecord> records = new ArrayList<>();
        var result = WalReader.read(walPath, records::add);

        assertEquals(4, records.size());
        assertFalse(result.truncatedTailDetected());

        var first = assertInstanceOf(WalRecord.Put.class, records.get(0));
        assertEquals("a", first.key());
        assertEquals("1", first.value());
        assertEquals(-1, first.ttlMillis());

        var second = assertInstanceOf(WalRecord.Put.class, records.get(1));
        assertEquals("b", second.key());
        assertEquals(60_000, second.ttlMillis());

        var third = assertInstanceOf(WalRecord.Invalidate.class, records.get(2));
        assertEquals("a", third.key());

        assertInstanceOf(WalRecord.Clear.class, records.get(3));
    }

    @Test
    @DisplayName("values containing multi-byte UTF-8 characters survive the round trip exactly")
    void nonAsciiValuesRoundTripExactly(@TempDir Path dir) throws Exception {
        Path walPath = dir.resolve("wal.log");
        String value = "café ☃ 😀"; // accented char, snowman, emoji (surrogate pair)
        try (var wal = new WriteAheadLog(walPath, WriteAheadLog.FsyncPolicy.ALWAYS)) {
            wal.appendPut("k", value, -1);
        }

        List<WalRecord> records = new ArrayList<>();
        WalReader.read(walPath, records::add);
        var put = assertInstanceOf(WalRecord.Put.class, records.get(0));
        assertEquals(value, put.value());
    }

    @Test
    @DisplayName("a byte flipped inside a written record is caught by the checksum, not silently accepted")
    void aFlippedByteFailsTheChecksum(@TempDir Path dir) throws Exception {
        Path walPath = dir.resolve("wal.log");
        try (var wal = new WriteAheadLog(walPath, WriteAheadLog.FsyncPolicy.ALWAYS)) {
            wal.appendPut("a", "1", -1);
            wal.appendPut("b", "2", -1);
        }

        byte[] bytes = java.nio.file.Files.readAllBytes(walPath);
        bytes[10] ^= 0xFF; // corrupt a byte inside the first record's payload
        java.nio.file.Files.write(walPath, bytes);

        List<WalRecord> records = new ArrayList<>();
        var result = WalReader.read(walPath, records::add);

        assertTrue(result.truncatedTailDetected());
        assertTrue(records.isEmpty(), "the corrupted first record must not be trusted");
    }
}
