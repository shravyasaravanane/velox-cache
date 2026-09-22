package com.velox.core.durability;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

/**
 * Replays a {@link WriteAheadLog}'s file, handing each valid {@link WalRecord} to a callback as
 * it is decoded.
 *
 * <h2>The one property that matters: never throw on a torn tail</h2>
 *
 * A process that crashes mid-{@code write()} (or mid-fsync, on {@link
 * WriteAheadLog.FsyncPolicy#NEVER}/{@code EVERYSEC}) can leave its log file ending in a partial
 * record: a length prefix with fewer payload bytes than promised, or a complete payload whose
 * checksum does not match because only some of it actually reached disk. Either way, that is
 * not an error to propagate -- it is <i>expected</i>, the entire reason the checksum and length
 * prefix exist. {@link #read} stops at the first such record, has already delivered every record
 * that read and verified cleanly before it, and reports exactly how many bytes of the file were
 * valid via {@link ReadResult#validByteLength()} -- which {@link DurableStore} uses to truncate
 * the file back to a clean boundary before any new record is ever appended to it again.
 *
 * <h2>Why a callback, not a {@code List<WalRecord>}</h2>
 *
 * The first version of this method collected every decoded record into a list and returned it,
 * for {@link RecoveryManager} to fold into its map afterward. That is simple, and wrong at
 * scale: it means replay's peak memory is the count of every operation ever written since the
 * last compaction, not the size of the final state -- normally the same order of magnitude, but
 * not the same thing, and a real difference once compaction has not run in a while. Found live,
 * not in a unit test: a demo that intentionally disabled compaction to run a long,
 * uncompacted write burst before simulating a crash produced a multi-hundred-megabyte list of
 * boxed {@code WalRecord} objects and threw {@code OutOfMemoryError} on recovery, well before
 * the far smaller final map was ever built. Streaming each record straight into the caller's
 * fold, one at a time, bounds peak memory by the state size instead -- the complexity the
 * compaction design was always supposed to guarantee, now actually true of the replay path too.
 *
 * @see DurableStore the crash-recovery test that exercises this directly
 */
final class WalReader {

    private WalReader() {
    }

    /** A defensive cap: a corrupted length prefix could otherwise ask for gigabytes and turn a
     * torn-write recovery into an out-of-memory crash instead of a clean stop. No real record
     * this project writes is anywhere near this size. */
    private static final int MAX_REASONABLE_PAYLOAD_BYTES = 64 * 1024 * 1024;

    record ReadResult(long validByteLength, boolean truncatedTailDetected) {
    }

    /** @param onRecord called once per valid record, in file order, as each is decoded --
     *                  never handed a partial or corrupt one */
    static ReadResult read(Path path, Consumer<WalRecord> onRecord) throws IOException {
        if (!Files.exists(path)) {
            return new ReadResult(0, false);
        }

        long validBytes = 0;
        boolean truncated = false;

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    break; // clean end of file: no partial record was even started
                }

                if (length < 0 || length > MAX_REASONABLE_PAYLOAD_BYTES) {
                    truncated = true; // an implausible length is itself a corruption signal
                    break;
                }

                byte[] payload = new byte[length];
                int storedCrc;
                try {
                    in.readFully(payload);
                    storedCrc = in.readInt();
                } catch (EOFException e) {
                    truncated = true; // a length was announced but the promised bytes never fully arrived
                    break;
                }

                CRC32C crc = new CRC32C();
                crc.update(payload);
                if ((int) crc.getValue() != storedCrc) {
                    truncated = true; // bytes arrived, but not the ones that were actually written
                    break;
                }

                onRecord.accept(parse(payload));
                validBytes += 4L + length + 4L; // length prefix + payload + checksum
            }
        }

        return new ReadResult(validBytes, truncated);
    }

    private static WalRecord parse(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte op = in.readByte();
            long timestamp = in.readLong();
            return switch (op) {
                case WriteAheadLog.OP_PUT -> {
                    String key = readString(in);
                    String value = readString(in);
                    long ttlMillis = in.readLong();
                    yield new WalRecord.Put(key, value, ttlMillis, timestamp);
                }
                case WriteAheadLog.OP_INVALIDATE -> new WalRecord.Invalidate(readString(in), timestamp);
                case WriteAheadLog.OP_CLEAR -> new WalRecord.Clear(timestamp);
                default -> throw new IOException("unknown WAL op byte: " + op
                        + " (a valid checksum on an unrecognised op means the format itself changed, "
                        + "not a torn write -- surfaced as a real error, not silently skipped)");
            };
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
