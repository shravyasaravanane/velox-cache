package com.velox.core.durability;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M8.1-M8.3: a durable key-value store -- every mutation goes to {@link WriteAheadLog} before
 * it is considered applied, {@link Compactor} periodically folds the log into a {@link
 * Snapshotter} snapshot so the log never grows without bound, and {@link RecoveryManager}
 * reconstructs everything on startup from whatever combination of snapshot and log tail exists
 * on disk -- including a log tail torn by a real crash.
 *
 * <h2>Why this is a separate store, not a durability layer bolted onto {@code Cache}</h2>
 *
 * {@code Cache<K, V>} is allowed to forget things -- that is its entire point, and {@link
 * com.velox.core.Cache}'s own class Javadoc says so directly: "a cache is never authoritative."
 * Durability is about never losing data that is supposed to survive a restart, which is close
 * to the opposite promise. Retrofitting persistence onto the eviction-bounded cache would mean
 * either persisting entries that are allowed to vanish anyway (wasted disk I/O, no real
 * guarantee) or quietly changing what "cache" means partway through the project. This is
 * instead a plain, durable KV store in its own right -- what a cache's real backing store (the
 * database, in {@code velox-server}'s case) already is, made small and self-contained enough to
 * build and prove correct here. {@code velox-cluster}'s {@code ClusterNode} is the natural place
 * to eventually swap this in underneath its in-memory {@code Cache<String, String>}, kept out
 * of scope here to keep this module's own correctness argument self-contained.
 *
 * <h2>Locking, honestly</h2>
 *
 * Every mutating method is {@code synchronized} on this store -- one lock, held for the WAL
 * append and the in-memory update together. That is not a high-throughput design (a real
 * production WAL would batch and pipeline writes across many callers); it is the simplest
 * design that is obviously correct, appropriate for what this module exists to prove: that the
 * recovery story is right, not that it is fast. {@link #get} does not take the lock -- it reads
 * a {@link ConcurrentHashMap} directly, safe to call from any thread at any time.
 */
public final class DurableStore implements Closeable {

    static final String WAL_FILE_NAME = "wal.log";

    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final Path directory;
    private final Path walPath;
    private final WriteAheadLog.FsyncPolicy fsyncPolicy;
    private final Snapshotter snapshotter;
    private final Compactor compactor;
    private final int compactEveryNOps;

    private WriteAheadLog wal;
    private int opsSinceCompaction;
    private volatile boolean lastRecoveryFoundCorruption;

    public DurableStore(Path directory, WriteAheadLog.FsyncPolicy fsyncPolicy, int compactEveryNOps) throws IOException {
        if (compactEveryNOps < 1) {
            throw new IllegalArgumentException("compactEveryNOps must be at least 1, got " + compactEveryNOps);
        }
        this.directory = directory;
        this.fsyncPolicy = fsyncPolicy;
        this.compactEveryNOps = compactEveryNOps;
        Files.createDirectories(directory);

        this.walPath = directory.resolve(WAL_FILE_NAME);
        this.snapshotter = new Snapshotter(directory);
        this.compactor = new Compactor(snapshotter);

        RecoveryManager.Result recovered = new RecoveryManager(directory, snapshotter).recover();
        data.putAll(recovered.entries());
        this.lastRecoveryFoundCorruption = recovered.walTailWasCorrupted();
        truncateWalToValidLength(recovered.walValidByteLength());

        this.wal = new WriteAheadLog(walPath, fsyncPolicy);
    }

    public synchronized void put(String key, String value, long ttlMillis) throws IOException {
        wal.appendPut(key, value, ttlMillis);
        data.put(key, value);
        maybeCompact();
    }

    public synchronized void invalidate(String key) throws IOException {
        wal.appendInvalidate(key);
        data.remove(key);
        maybeCompact();
    }

    public synchronized void clear() throws IOException {
        wal.appendClear();
        data.clear();
        maybeCompact();
    }

    public String get(String key) {
        return data.get(key);
    }

    public int size() {
        return data.size();
    }

    /** @return whether the write-ahead log's tail was found torn/corrupted the last time this
     *          store (or a predecessor opened on the same directory) recovered -- the signal a
     *          real deployment would alert on, since it means the previous process crashed
     *          mid-write rather than shutting down cleanly. Does not itself indicate any data
     *          loss beyond what that crash already caused: recovery already discarded exactly
     *          the torn tail and nothing else. */
    public boolean lastRecoveryFoundCorruption() {
        return lastRecoveryFoundCorruption;
    }

    /** Forces a snapshot + WAL truncation immediately, rather than waiting for
     * {@code compactEveryNOps} mutations to accumulate. */
    public synchronized void compactNow() throws IOException {
        wal.close();
        compactor.compact(data, walPath);
        wal = new WriteAheadLog(walPath, fsyncPolicy);
        opsSinceCompaction = 0;
    }

    private void maybeCompact() throws IOException {
        if (++opsSinceCompaction >= compactEveryNOps) {
            compactNow();
        }
    }

    private void truncateWalToValidLength(long validBytes) throws IOException {
        if (!Files.exists(walPath)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
            channel.truncate(validBytes);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        wal.close();
    }
}
