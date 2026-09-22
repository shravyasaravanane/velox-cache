package com.velox.core.durability;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

/**
 * M8.1: a write-ahead log -- every mutation is appended here, durably, <b>before</b> {@link
 * DurableStore} considers it applied. If the process dies the instant after, {@link WalReader}
 * plus a snapshot can reconstruct exactly what happened up to the last byte that made it to
 * disk.
 *
 * <h2>Record format</h2>
 *
 * <pre>{@code
 * [4 bytes: payload length]
 * [payload: 1-byte op, 8-byte timestamp, then op-specific fields -- see WalRecord]
 * [4 bytes: CRC32C of the payload]
 * }</pre>
 *
 * <p>The length prefix lets a reader know exactly how many payload bytes to expect without
 * scanning for a delimiter; the trailing checksum is what turns "the process died mid-write, so
 * this record is truncated or torn" from silent corruption into something {@link WalReader} can
 * detect and stop cleanly at -- see its own Javadoc, and {@link DurableStore}'s crash-recovery
 * test, for what happens on the read side.
 *
 * <h2>CRC32C, not CRC32</h2>
 *
 * {@link java.util.zip.CRC32C} (Castagnoli polynomial, RFC 3720) is what modern storage engines
 * actually use -- it has better error-detection properties than the classic CRC32 for the short,
 * bursty-error patterns a torn disk write produces, and the JDK has shipped a hardware-
 * accelerated implementation since Java 9. There is no reason to hand-roll a weaker one.
 *
 * <h2>Fsync policy: the same three choices Redis's AOF gives you</h2>
 *
 * <ul>
 *   <li>{@link FsyncPolicy#ALWAYS} -- fsync after every single append. No data loss on a crash,
 *       at the cost of a disk-flush latency on every write.</li>
 *   <li>{@link FsyncPolicy#EVERYSEC} -- a background thread fsyncs at most once per second.
 *       Bounded loss window (at most ~1 second of writes), and every write pays only a buffered
 *       stream write, not a flush.</li>
 *   <li>{@link FsyncPolicy#NEVER} -- no explicit fsync at all (the OS flushes on its own
 *       schedule); {@link #close()} still syncs, so a clean shutdown never loses anything --
 *       only an actual crash can.</li>
 * </ul>
 *
 * This is a real, named trade-off in real systems, not a simplification invented for this
 * project -- naming it after the system that made it well-known is more honest than pretending
 * it is novel.
 */
public final class WriteAheadLog implements Closeable {

    public enum FsyncPolicy {
        ALWAYS, EVERYSEC, NEVER
    }

    static final byte OP_PUT = 1;
    static final byte OP_INVALIDATE = 2;
    static final byte OP_CLEAR = 3;

    private final FileOutputStream fileOut;
    private final DataOutputStream out;
    private final FsyncPolicy policy;
    private final ScheduledExecutorService fsyncScheduler;

    public WriteAheadLog(Path path, FsyncPolicy policy) throws IOException {
        this.policy = policy;
        this.fileOut = new FileOutputStream(path.toFile(), true); // append: never clobber a recovered tail
        this.out = new DataOutputStream(new BufferedOutputStream(fileOut));

        if (policy == FsyncPolicy.EVERYSEC) {
            ThreadFactory daemonFactory = runnable -> {
                Thread thread = new Thread(runnable, "velox-wal-fsync");
                thread.setDaemon(true);
                return thread;
            };
            this.fsyncScheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory);
            fsyncScheduler.scheduleWithFixedDelay(this::fsyncQuietly, 1, 1, TimeUnit.SECONDS);
        } else {
            this.fsyncScheduler = null;
        }
    }

    public synchronized void appendPut(String key, String value, long ttlMillis) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(buffer);
        payload.writeByte(OP_PUT);
        payload.writeLong(System.currentTimeMillis());
        payload.writeInt(keyBytes.length);
        payload.write(keyBytes);
        payload.writeInt(valueBytes.length);
        payload.write(valueBytes);
        payload.writeLong(ttlMillis);

        writeRecord(buffer.toByteArray());
    }

    public synchronized void appendInvalidate(String key) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(buffer);
        payload.writeByte(OP_INVALIDATE);
        payload.writeLong(System.currentTimeMillis());
        payload.writeInt(keyBytes.length);
        payload.write(keyBytes);

        writeRecord(buffer.toByteArray());
    }

    public synchronized void appendClear() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(buffer);
        payload.writeByte(OP_CLEAR);
        payload.writeLong(System.currentTimeMillis());

        writeRecord(buffer.toByteArray());
    }

    private void writeRecord(byte[] payload) throws IOException {
        CRC32C crc = new CRC32C();
        crc.update(payload);

        out.writeInt(payload.length);
        out.write(payload);
        out.writeInt((int) crc.getValue());

        if (policy == FsyncPolicy.ALWAYS) {
            flushAndSync();
        }
    }

    private void flushAndSync() throws IOException {
        out.flush();
        fileOut.getFD().sync();
    }

    private void fsyncQuietly() {
        try {
            flushAndSync();
        } catch (IOException ignored) {
            // A background fsync failing is not fatal by itself -- the next scheduled attempt,
            // or close()'s own sync, gets another chance. Losing the unsynced tail on an actual
            // crash is exactly EVERYSEC's documented, bounded risk, not a new failure mode.
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (fsyncScheduler != null) {
            fsyncScheduler.shutdownNow();
        }
        flushAndSync();
        out.close();
    }
}
