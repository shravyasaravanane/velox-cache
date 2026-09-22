package com.velox.core;

import com.velox.core.concurrent.DrainStatus;
import com.velox.core.concurrent.LossyReadBuffer;
import com.velox.core.concurrent.ShardRouter;
import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.ExpiryEngine;
import com.velox.core.loader.SingleFlight;
import com.velox.core.policy.EvictionPolicy;
import com.velox.core.stats.CacheStats;
import com.velox.core.structure.Node;
import com.velox.core.util.Hashing;
import com.velox.core.util.Invariants;
import com.velox.core.util.Ticker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A thread-safe cache: the single-threaded engine, split into independent shards.
 *
 * <h2>The problem</h2>
 *
 * An LRU {@code get} is not a read: it moves the entry to the front of the recency
 * list. So a cache guarded by one lock serialises every request, and adding threads
 * makes it <i>slower</i> (see {@code docs/benchmarks/thread-scaling.md}: two threads
 * delivered half the throughput of one). This class removes the bottleneck in two
 * layers.
 *
 * <h2>Layer 1: sharding</h2>
 *
 * The key space is split across N shards, each a complete {@link VeloxCache} with its
 * own lock, chosen by the high bits of the key's hash ({@link ShardRouter}). Two
 * requests wait for each other only if their keys land in the same shard, so
 * contention drops by roughly a factor of N.
 *
 * <h2>Layer 2: buffered reads (opt-in, and measured not to help)</h2>
 *
 * <b>This layer is off by default.</b> It was built as designed, and then benchmarked: it
 * was never clearly faster than plain exclusive reads and was roughly 10-28% slower in some
 * configurations (see {@code docs/benchmarks/thread-scaling.md}), because a shared lock
 * still updates the lock word atomically. It stays available through
 * {@code CacheBuilder.bufferedReads(true)}, and is what a future lock-free read path
 * would replace.
 *
 * <p>Even within a shard, every read would need the exclusive lock to reorder the
 * recency list. Instead a <b>hit</b> takes only the shared (read) lock, so many
 * readers proceed together, finds its value, and drops "I used this entry" into a
 * lock-free {@link LossyReadBuffer}. The recency list is updated later, in a batch,
 * by whoever drains the buffer under the exclusive lock ({@link DrainStatus} makes
 * sure a request to drain is never lost). The batch is applied before every write, so
 * eviction decisions always see reads up to the last drain.
 *
 * <p>Anything that is not a plain hit (a miss, an expired entry) falls back to the
 * exclusive path, which is the ordinary single-threaded engine. That is deliberate:
 * misses are followed by a load from the database, which costs orders of magnitude
 * more than an uncontended lock, and they need bookkeeping (policy miss hooks, lazy
 * expiry) that must be exclusive.
 *
 * <h2>Rules that keep it correct</h2>
 *
 * <ul>
 *   <li><b>One lock at a time.</b> No operation ever holds two shard locks, so shards
 *       cannot deadlock against each other.</li>
 *   <li><b>User code never runs under a lock.</b> Loaders run before the write lock is
 *       taken, and removal listeners are queued under the lock but delivered after it is
 *       released. A listener or loader that calls back into the cache cannot deadlock.</li>
 * </ul>
 *
 * <h2>The costs, stated plainly</h2>
 *
 * <ul>
 *   <li><b>Eviction is locally optimal, not globally.</b> Each shard has {@code 1/N} of
 *       the capacity and evicts on its own. A shard that happens to receive many hot
 *       keys evicts entries a single global LRU would have kept. The hit-ratio cost is
 *       measured, not assumed (see the benchmark notes).</li>
 *   <li><b>An entry can weigh at most {@code maximumWeight / N}.</b> Each shard has its
 *       own budget, so a weight-bounded cache with 16 shards refuses an entry heavier
 *       than one sixteenth of the total even though the total would hold it. The shard
 *       count is reduced automatically if the budget is too small for it.</li>
 *   <li><b>Reads can be dropped.</b> Under extreme contention a read record is discarded
 *       rather than making the reader wait, so recency is slightly less accurate.
 *       {@link #droppedReads()} reports how often.</li>
 *   <li><b>{@code expireAfterAccess} disables buffered reads.</b> It must reposition an
 *       entry in the expiry structure on every hit, which needs the exclusive lock, so
 *       reads take it. Sharding still applies.</li>
 *   <li><b>Whole-cache operations are not atomic across shards.</b> {@link #size()},
 *       {@link #invalidateAll()} and {@link #stats()} visit shards one at a time.</li>
 * </ul>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class ShardedCache<K, V> implements Cache<K, V> {

    /**
     * At most this many drain passes in a row while readers keep refilling the buffer.
     * Without a bound, a steady stream of hits could keep one thread draining forever
     * while holding the write lock, starving every writer.
     */
    private static final int MAX_DRAIN_PASSES = 3;

    /** One shard: an engine, the lock that guards it, and the buffer that spares readers the lock. */
    private static final class Shard<K, V> {
        final VeloxCache<K, V> engine;
        final ReentrantReadWriteLock lock;
        final ReentrantReadWriteLock.ReadLock readLock;
        final ReentrantReadWriteLock.WriteLock writeLock;
        final LossyReadBuffer<Node<K, V>> readBuffer;      // null when buffered reads are off
        final DrainStatus drainStatus = new DrainStatus();

        Shard(VeloxCache<K, V> engine, LossyReadBuffer<Node<K, V>> readBuffer) {
            var lock = new ReentrantReadWriteLock();
            this.engine = engine;
            this.lock = lock;
            this.readLock = lock.readLock();
            this.writeLock = lock.writeLock();
            this.readBuffer = readBuffer;
        }
    }

    private final Shard<K, V>[] shards;
    private final ShardRouter router;
    private final SingleFlight<K, V> flight = new SingleFlight<>();
    private final boolean bufferedReads;
    private final long maximumWeight;
    private final int maximumEntries;

    private static final System.Logger LOG = System.getLogger(ShardedCache.class.getName());
    private static final AtomicInteger SWEEPER_IDS = new AtomicInteger();

    /** The background cleanup thread, or {@code null} if none was requested. */
    private final ScheduledExecutorService sweeper;

    /**
     * @param capacity        the TOTAL capacity, split across the shards
     * @param requestedShards desired shard count; rounded up to a power of two, then
     *                        reduced if the capacity is too small to give every shard a share
     * @param policyFactory   builds a shard's eviction policy from that shard's size hint
     * @param expiryFactory   builds a shard's expiry engine
     * @param expiryConfig    how entries expire
     * @param ticker          the time source
     * @param listener        told when values leave any shard; may be {@code null}
     * @param bufferedReads   whether hits use the shared lock and a read buffer
     * @param readBufferSize  slots per shard's read buffer; a power of two
     * @param cleanUpEveryNanos how often a background thread sweeps expired entries, or a
     *                        non-positive value for no background thread
     */
    ShardedCache(Capacity<K, V> capacity, int requestedShards,
                 IntFunction<EvictionPolicy<K, V>> policyFactory, Supplier<ExpiryEngine<K, V>> expiryFactory,
                 ExpiryConfig expiryConfig, Ticker ticker, RemovalListener<K, V> listener,
                 boolean bufferedReads, int readBufferSize, long cleanUpEveryNanos) {
        Objects.requireNonNull(capacity, "capacity");
        this.maximumWeight = capacity.maximumWeight();
        this.maximumEntries = capacity.maximumEntries();

        int shardCount = effectiveShardCount(requestedShards, capacity.maximumWeight());
        this.router = new ShardRouter(shardCount);
        // Buffered reads cannot serve expire-after-access: each hit must move the entry's
        // deadline, which mutates the expiry structure and so needs the exclusive lock.
        this.bufferedReads = bufferedReads && !expiryConfig.hasAccessTtl();

        @SuppressWarnings("unchecked")
        Shard<K, V>[] made = (Shard<K, V>[]) new Shard[shardCount];
        long base = capacity.maximumWeight() / shardCount;
        long extra = capacity.maximumWeight() % shardCount;

        for (int i = 0; i < shardCount; i++) {
            long share = base + (i < extra ? 1 : 0);              // shares differ by at most 1 and sum to the total
            Capacity<K, V> shardCapacity = capacity.isCountBounded()
                    ? Capacity.entries((int) share)
                    : Capacity.weighted(share, capacity.weigher(), Math.max(1, capacity.sizingHint() / shardCount));

            var engine = new VeloxCache<K, V>(shardCapacity, policyFactory.apply(shardCapacity.sizingHint()),
                    expiryConfig, ticker, expiryFactory.get(), listener, false);
            made[i] = new Shard<>(engine, this.bufferedReads ? new LossyReadBuffer<>(readBufferSize) : null);
        }
        this.shards = made;

        if (cleanUpEveryNanos > 0) {
            // A daemon thread, so an application that forgets to close the cache can still exit.
            this.sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "velox-sweeper-" + SWEEPER_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            // Fixed DELAY, not fixed rate: if a sweep is slow the next waits, rather than piling up.
            this.sweeper.scheduleWithFixedDelay(this::sweep, cleanUpEveryNanos, cleanUpEveryNanos, TimeUnit.NANOSECONDS);
        } else {
            this.sweeper = null;
        }
    }

    /**
     * One background sweep. A failure must not kill the schedule: a periodic task that
     * throws is silently cancelled by the executor, after which dead entries would
     * accumulate again with nothing to show for it.
     */
    private void sweep() {
        try {
            cleanUp();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "background cleanup failed; it will try again", e);
        }
    }

    /** Stops the background cleanup thread, if there is one. Safe to call more than once. */
    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    /** @return whether a background cleanup thread is running */
    public boolean hasBackgroundCleanUp() {
        return sweeper != null && !sweeper.isShutdown();
    }

    /** Never more shards than there are units of capacity to share out, and always a power of two. */
    private static int effectiveShardCount(int requested, long totalCapacity) {
        int wanted = new ShardRouter(Math.max(1, requested)).shardCount();
        int affordable = Integer.highestOneBit((int) Math.min(totalCapacity, 1 << 20));
        return Math.max(1, Math.min(wanted, affordable));
    }

    private Shard<K, V> shardFor(int hash) {
        return shards[router.shardFor(hash)];
    }

    // ------------------------------------------------------------------
    //  Reads
    // ------------------------------------------------------------------

    @Override
    public V getIfPresent(K key) {
        Objects.requireNonNull(key, "key");
        int hash = Hashing.spread(key);
        Shard<K, V> shard = shardFor(hash);
        return bufferedReads ? readBuffered(shard, key, hash) : readExclusive(shard, key);
    }

    /**
     * The fast path. A hit needs only the shared lock, so hits on one shard run in
     * parallel; the reorder is deferred through the buffer.
     */
    private V readBuffered(Shard<K, V> shard, K key, int hash) {
        Node<K, V> node;
        V value;
        shard.readLock.lock();
        try {
            node = shard.engine.findLive(key, hash);
            value = node == null ? null : node.value();
        } finally {
            shard.readLock.unlock();
        }

        if (node == null) {
            return readExclusive(shard, key);        // a miss (or expired entry) needs the exclusive path
        }

        shard.engine.recordHit();
        int outcome = shard.readBuffer.offer(node);   // lock-free; may be dropped
        if (outcome == LossyReadBuffer.ACCEPTED_DRAIN_ADVISED || outcome == LossyReadBuffer.DROPPED_FULL) {
            if (shard.drainStatus.requestDrain()) {
                tryDrain(shard);
            }
        }
        return value;
    }

    /** The slow path: the ordinary engine under the exclusive lock. Also does all miss bookkeeping. */
    private V readExclusive(Shard<K, V> shard, K key) {
        V value;
        shard.writeLock.lock();
        try {
            drainBuffer(shard);                       // recency current before this operation
            value = shard.engine.getIfPresent(key);
        } finally {
            shard.writeLock.unlock();
        }
        shard.engine.dispatchRemovals();              // user code runs only after the lock is released
        return value;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(loader, "loader");

        V cached = getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        // The loader runs here, holding NO lock, and only the leader stores the value.
        return flight.load(key, k -> {
            V loaded = loader.apply(k);
            if (loaded != null) {
                put(k, loaded);
            }
            return loaded;
        });
    }

    @Override
    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key");
        Shard<K, V> shard = shardFor(Hashing.spread(key));
        shard.readLock.lock();
        try {
            return shard.engine.containsKey(key);
        } finally {
            shard.readLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    //  Writes
    // ------------------------------------------------------------------

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Shard<K, V> shard = shardFor(Hashing.spread(key));
        shard.writeLock.lock();
        try {
            drainBuffer(shard);
            shard.engine.put(key, value);
        } finally {
            shard.writeLock.unlock();
        }
        shard.engine.dispatchRemovals();
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Shard<K, V> shard = shardFor(Hashing.spread(key));
        shard.writeLock.lock();
        try {
            drainBuffer(shard);
            shard.engine.put(key, value, ttl);
        } finally {
            shard.writeLock.unlock();
        }
        shard.engine.dispatchRemovals();
    }

    @Override
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        Shard<K, V> shard = shardFor(Hashing.spread(key));
        shard.writeLock.lock();
        try {
            shard.engine.invalidate(key);
        } finally {
            shard.writeLock.unlock();
        }
        shard.engine.dispatchRemovals();
    }

    @Override
    public void invalidateAll() {
        for (Shard<K, V> shard : shards) {          // one shard at a time: never two locks held
            shard.writeLock.lock();
            try {
                shard.engine.invalidateAll();
            } finally {
                shard.writeLock.unlock();
            }
            shard.engine.dispatchRemovals();
        }
    }

    @Override
    public void cleanUp() {
        for (Shard<K, V> shard : shards) {
            shard.writeLock.lock();
            try {
                drainBuffer(shard);
                shard.engine.cleanUp();
            } finally {
                shard.writeLock.unlock();
            }
            shard.engine.dispatchRemovals();
        }
    }

    // ------------------------------------------------------------------
    //  Maintenance: applying buffered reads
    // ------------------------------------------------------------------

    /** Drains without waiting: if someone else holds the lock, they are draining (or writing) anyway. */
    private void tryDrain(Shard<K, V> shard) {
        if (shard.writeLock.tryLock()) {
            try {
                drainBuffer(shard);
            } finally {
                shard.writeLock.unlock();
            }
        }
    }

    /**
     * Applies buffered read records to the policy. The caller MUST hold the write lock.
     *
     * <p>The {@link DrainStatus} handshake makes a request that arrives mid-drain trigger
     * another pass. The number of passes is capped so a steady stream of hits cannot keep
     * this thread draining, lock held, indefinitely; a leftover request is simply left
     * pending for the next reader or writer.
     */
    private void drainBuffer(Shard<K, V> shard) {
        if (shard.readBuffer == null) {
            return;
        }
        for (int pass = 0; pass < MAX_DRAIN_PASSES; pass++) {
            shard.drainStatus.beginProcessing();
            shard.readBuffer.drain(shard.engine::applyBufferedAccess);
            if (!shard.drainStatus.endProcessing()) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    //  Introspection
    // ------------------------------------------------------------------

    @Override
    public int size() {
        long total = 0;
        for (Shard<K, V> shard : shards) {
            shard.readLock.lock();
            try {
                total += shard.engine.size();
            } finally {
                shard.readLock.unlock();
            }
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    @Override
    public long weightedSize() {
        long total = 0;
        for (Shard<K, V> shard : shards) {
            shard.readLock.lock();
            try {
                total += shard.engine.weightedSize();
            } finally {
                shard.readLock.unlock();
            }
        }
        return total;
    }

    @Override
    public int maximumSize() {
        return maximumEntries;
    }

    @Override
    public long maximumWeight() {
        return maximumWeight;
    }

    @Override
    public CacheStats stats() {
        CacheStats total = CacheStats.EMPTY;
        for (Shard<K, V> shard : shards) {
            total = total.plus(shard.engine.stats());
        }
        return total.withLoadCounts(flight.loads(), flight.failures(), flight.coalesced());
    }

    /**
     * Test seam: whether the calling thread holds the lock (shared or exclusive) of the
     * shard that {@code key} routes to.
     *
     * <p>Lets a test observe, from inside a clock read, that the fast read path really is
     * running under the shared lock. That property cannot be checked by behaviour alone: a
     * racy reader can only ever see a false miss (which the exclusive fallback repairs) or
     * a value that was valid a moment ago, and x86 does not reorder the stores that would
     * expose a half-built entry, so the race is real under the Java memory model but
     * invisible on this hardware.
     */
    boolean holdsLockFor(K key) {
        Shard<K, V> shard = shardFor(Hashing.spread(key));
        return shard.lock.getReadHoldCount() > 0 || shard.lock.isWriteLockedByCurrentThread();
    }

    /** @return the number of shards in use, a power of two */
    public int shardCount() {
        return shards.length;
    }

    /**
     * @return the current entry count of each shard, in shard order -- how evenly (or not)
     *         {@link ShardRouter} is spreading keys across shards, and the basis for a live
     *         "per-shard load" panel. A snapshot, not atomic across shards: like {@link #size()},
     *         it is a sum (here, per-shard) of values that can each keep moving while this loops.
     */
    public int[] shardSizes() {
        int[] sizes = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            Shard<K, V> shard = shards[i];
            shard.readLock.lock();
            try {
                sizes[i] = shard.engine.size();
            } finally {
                shard.readLock.unlock();
            }
        }
        return sizes;
    }

    /** @return how many read records were discarded because a buffer was full or contended */
    public long droppedReads() {
        long total = 0;
        for (Shard<K, V> shard : shards) {
            if (shard.readBuffer != null) {
                total += shard.readBuffer.dropped();
            }
        }
        return total;
    }

    /** @return whether hits use the shared lock and a read buffer */
    public boolean usesBufferedReads() {
        return bufferedReads;
    }

    /** @return the name of the eviction policy in use, e.g. {@code "LRU"} */
    public String policyName() {
        return shards[0].engine.policyName();
    }

    /** @return the name of the expiry engine in use, e.g. {@code "INDEXED_HEAP"} */
    public String expiryEngineName() {
        return shards[0].engine.expiryEngineName();
    }

    /**
     * Verifies every shard is internally consistent, and that every entry lives in the
     * shard its key routes to. Does nothing unless {@code -Dvelox.assertions=true}.
     *
     * <p>Takes each shard's write lock in turn, so it is safe to call while other threads
     * are working, and it applies pending buffered reads first.
     */
    public void assertInvariants() {
        if (!Invariants.ENABLED) {
            return;
        }
        for (int index = 0; index < shards.length; index++) {
            Shard<K, V> shard = shards[index];
            final int expected = index;
            shard.writeLock.lock();
            try {
                drainBuffer(shard);
                shard.engine.assertInvariants();
                shard.engine.forEachNode(node -> {
                    if (router.shardFor(node.hash()) != expected) {
                        throw new IllegalStateException("entry " + node.key() + " lives in shard " + expected
                                + " but its key routes to shard " + router.shardFor(node.hash()));
                    }
                });
            } finally {
                shard.writeLock.unlock();
            }
        }
    }

    @Override
    public String toString() {
        return "ShardedCache[" + policyName() + ", " + shards.length + " shards, " + size() + " entries, "
                + (bufferedReads ? "buffered" : "exclusive") + " reads, " + stats() + "]";
    }
}
