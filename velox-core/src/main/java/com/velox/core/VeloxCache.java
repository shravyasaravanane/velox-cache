package com.velox.core;

import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.ExpiryEngine;
import com.velox.core.expiry.HeapExpiryEngine;
import com.velox.core.loader.SingleFlight;
import com.velox.core.policy.EvictionPolicy;
import com.velox.core.stats.CacheStats;
import com.velox.core.stats.StatsCounter;
import com.velox.core.structure.Node;
import com.velox.core.structure.OpenAddressingMap;
import com.velox.core.util.Hashing;
import com.velox.core.util.Invariants;
import com.velox.core.util.Ticker;

import java.time.Duration;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The cache engine: a bounded map with a pluggable eviction policy, optional
 * time-based expiry, and capacity measured either in entries or in weight.
 *
 * <h2>Three structures, one set of nodes</h2>
 *
 * <pre>
 *   OpenAddressingMap        EvictionPolicy            ExpiryEngine
 *   "where is this key?"     "what is expendable?"     "what is due to expire?"
 *         O(1)                       O(1)                    O(log n) heap
 *
 *   Every Node lives in the map, in the policy's structures, and (if it has a
 *   TTL) in the expiry engine -- the same object, referenced three ways.
 * </pre>
 *
 * The consequence to keep in mind: an entry lives in up to three places, so
 * <b>every removal must update all of them</b>. That is why eviction,
 * invalidation and expiry all funnel through one method, {@link #detach}.
 * Removing from two and forgetting the third leaks memory or leaves a phantom
 * entry -- exactly what {@link #assertInvariants()} checks for.
 *
 * <h2>Capacity is a weight budget</h2>
 *
 * The cache tracks a running {@code weightedSize} and refuses to exceed
 * {@code maximumWeight}. A count-bounded cache is the special case where every
 * entry weighs 1, so there is one eviction mechanism, not two. Consequences:
 *
 * <ul>
 *   <li><b>Eviction is a loop.</b> One large newcomer may need several small
 *       entries evicted to make room.</li>
 *   <li><b>An entry heavier than the whole cache is refused</b> outright,
 *       without evicting anything. Flushing every useful entry to make room for
 *       something that still would not fit would be pure loss.</li>
 *   <li><b>Overwriting with a lighter or equal value updates in place.</b>
 *       Overwriting with a <i>heavier</i> value is treated as remove-and-insert
 *       (see {@link #putInternal}).</li>
 * </ul>
 *
 * <h2>How expired entries actually get removed</h2>
 *
 * There is no background thread (yet -- it arrives with locking in Tier 2).
 * Instead expiry is enforced three ways:
 *
 * <ol>
 *   <li><b>Lazily on read.</b> A read that finds an expired entry removes it
 *       and reports a miss. Expired data is never served.</li>
 *   <li><b>Opportunistically on write.</b> Every {@code put} first drains all
 *       due entries from the expiry engine, so a dead entry is reaped before a
 *       live one is evicted to make room.</li>
 *   <li><b>On demand</b> via {@link #cleanUp()}.</li>
 * </ol>
 *
 * <h2>The clock is only read when needed</h2>
 *
 * Reading the clock costs real time (tens of nanoseconds), so a cache with no
 * TTLs configured never reads it.
 *
 * <h2>Removal notifications</h2>
 *
 * A {@link RemovalListener} is told whenever a value leaves (evicted, expired,
 * replaced or invalidated) and why. Because a listener is <i>user code</i>, it is
 * never run in the middle of an update: {@link #detach} only <b>queues</b> a
 * notification, and the queue is delivered by {@link #dispatchRemovals} once the
 * public operation has finished and every invariant holds. See
 * {@link RemovalListener} for the full contract.
 *
 * <h2>Thread safety</h2>
 *
 * <b>None.</b> This class is single-threaded, deliberately, and that is
 * correct for now. Note {@link #getIfPresent} updates the policy, so a
 * <i>read</i> mutates shared structure -- the central problem of Tier 2.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class VeloxCache<K, V> implements Cache<K, V> {

    /** Key to node. Answers "where is it?" in O(1). */
    private final OpenAddressingMap<K, V> data;

    /** Answers "what should leave?". */
    private final EvictionPolicy<K, V> policy;

    /** Answers "what is due to expire?". */
    private final ExpiryEngine<K, V> expiry;

    private final ExpiryConfig expiryConfig;
    private final Ticker ticker;
    private final SplittableRandom jitterRandom = new SplittableRandom(0x717EL);

    /** The capacity budget; for a count-bounded cache this is the entry limit. */
    private final long maximumWeight;

    /** The entry limit for a count-bounded cache, or -1 when bounded by weight. */
    private final int maximumEntries;

    /** Assigns each entry its weight; {@code null} means every entry weighs 1. */
    private final Weigher<K, V> weigher;

    /** The total weight of the entries held. Maintained incrementally; see {@link #detach}. */
    private long weightedSize;

    private final StatsCounter stats = new StatsCounter();

    /**
     * Makes concurrent misses on one key share a single load. It is separate from
     * the cache's own state, and thread-safe on its own, because loads are slow and
     * must never run while a cache lock is held.
     */
    private final SingleFlight<K, V> flight = new SingleFlight<>();

    private static final System.Logger LOG = System.getLogger(VeloxCache.class.getName());

    /** Told when a value leaves the cache; {@code null} if nobody is listening. */
    private final RemovalListener<K, V> removalListener;

    /**
     * Removals waiting to be delivered. Only ever filled when a listener exists.
     *
     * <p>Concurrent because in the sharded cache removals are QUEUED while the shard
     * lock is held but DELIVERED after it is released, by whichever thread gets there
     * first: several threads may be adding to and draining this queue at once.
     */
    private final ConcurrentLinkedQueue<Removal<K, V>> pendingRemovals = new ConcurrentLinkedQueue<>();

    /**
     * True while one thread is delivering notifications. A second thread (or a
     * re-entrant call from a listener) that finds it set simply leaves its
     * notifications in the queue for the thread already delivering.
     */
    private final AtomicBoolean dispatching = new AtomicBoolean();

    /**
     * Whether public operations deliver notifications themselves. True for a
     * standalone cache. A shard inside a {@link ShardedCache} sets this false, because
     * the shard is being used under a lock and the owner delivers AFTER unlocking, so
     * user code never runs while a lock is held.
     */
    private final boolean dispatchInline;

    /**
     * Test seam: runs on the delivering thread after the queue has been emptied but BEFORE
     * the delivery flag is released. That is the exact window in which another thread can
     * queue a notification whose own delivery attempt will fail (the flag is still held),
     * and which the re-check after the release exists to cover. It is a few nanoseconds
     * wide in real life, so the only way to test it deterministically is to stop there.
     */
    volatile Runnable beforeDeliveryFlagRelease;

    /** One queued notification. */
    private record Removal<K, V>(K key, V value, RemovalCause cause) {
    }

    /**
     * Creates a count-bounded cache with no configured expiry. Entries still
     * expire if given a per-entry TTL through {@link #put(Object, Object, Duration)}.
     *
     * @param maximumSize the most entries to hold; must be at least 1
     * @param policy      the eviction strategy
     */
    public VeloxCache(int maximumSize, EvictionPolicy<K, V> policy) {
        this(Capacity.entries(maximumSize), policy, ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>());
    }

    /**
     * Creates a count-bounded cache.
     *
     * @param maximumSize  the most entries to hold; must be at least 1
     * @param policy       the eviction strategy
     * @param expiryConfig how entries expire
     * @param ticker       the time source; tests pass a fake one
     * @param expiry       the engine that schedules expiry
     */
    public VeloxCache(int maximumSize, EvictionPolicy<K, V> policy, ExpiryConfig expiryConfig,
                      Ticker ticker, ExpiryEngine<K, V> expiry) {
        this(Capacity.entries(maximumSize), policy, expiryConfig, ticker, expiry);
    }

    /**
     * @param capacity     how full the cache may get: by entry count or by weight
     * @param policy       the eviction strategy
     * @param expiryConfig how entries expire
     * @param ticker       the time source; tests pass a fake one
     * @param expiry       the engine that schedules expiry
     */
    public VeloxCache(Capacity<K, V> capacity, EvictionPolicy<K, V> policy, ExpiryConfig expiryConfig,
                      Ticker ticker, ExpiryEngine<K, V> expiry) {
        this(capacity, policy, expiryConfig, ticker, expiry, null);
    }

    /**
     * @param capacity        how full the cache may get: by entry count or by weight
     * @param policy          the eviction strategy
     * @param expiryConfig    how entries expire
     * @param ticker          the time source; tests pass a fake one
     * @param expiry          the engine that schedules expiry
     * @param removalListener told when values leave the cache; may be {@code null}
     */
    public VeloxCache(Capacity<K, V> capacity, EvictionPolicy<K, V> policy, ExpiryConfig expiryConfig,
                      Ticker ticker, ExpiryEngine<K, V> expiry, RemovalListener<K, V> removalListener) {
        this(capacity, policy, expiryConfig, ticker, expiry, removalListener, true);
    }

    /**
     * The constructor {@link ShardedCache} uses for its shards.
     *
     * @param dispatchInline whether public operations deliver removal notifications
     *                       themselves; a shard passes {@code false} and the owner delivers
     *                       them after releasing the shard lock
     */
    VeloxCache(Capacity<K, V> capacity, EvictionPolicy<K, V> policy, ExpiryConfig expiryConfig,
               Ticker ticker, ExpiryEngine<K, V> expiry, RemovalListener<K, V> removalListener,
               boolean dispatchInline) {
        Objects.requireNonNull(capacity, "capacity");
        this.removalListener = removalListener;
        this.dispatchInline = dispatchInline;
        this.maximumWeight = capacity.maximumWeight();
        this.maximumEntries = capacity.maximumEntries();
        this.weigher = capacity.weigher();
        this.policy = Objects.requireNonNull(policy, "policy");
        this.expiryConfig = Objects.requireNonNull(expiryConfig, "expiryConfig");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.expiry = Objects.requireNonNull(expiry, "expiry");
        // Pre-size the table so a cache running at its limit never has to resize.
        this.data = new OpenAddressingMap<>(capacity.sizingHint());
    }

    // ------------------------------------------------------------------
    //  Reads
    // ------------------------------------------------------------------

    @Override
    public V getIfPresent(K key) {
        V value = lookup(key);
        afterOperation();            // a lazily-expired entry is reported once the read is done
        return value;
    }

    private V lookup(K key) {
        Objects.requireNonNull(key, "key");
        int hash = Hashing.spread(key);

        Node<K, V> node = data.get(key, hash);

        if (node == null) {
            return miss(key);
        }

        // Only entries with a deadline pay for a clock read. On a cache with no
        // TTLs this whole block is skipped.
        if (node.hasDeadline()) {
            long now = ticker.read();

            if (isExpired(node, now)) {
                // Lazy expiry: found it, but it is dead. Remove it and report
                // a miss -- expired data must never be served.
                data.remove(key, hash);
                detach(node, RemovalCause.EXPIRED);
                return miss(key);
            }

            if (expiryConfig.hasAccessTtl()) {
                // Expire-after-access: this read restarts the idle clock.
                recomputeDeadline(node, now);
            }
        }

        // The entry was used, so its position in the eviction order changes.
        // This is the line that makes a read a write.
        policy.onAccess(node);
        stats.recordHit();
        return node.value();
    }

    private V miss(K key) {
        stats.recordMiss();
        // Tell the policy about the miss. LRU ignores it; LFU-aging counts it;
        // ARC and 2Q (Tier 3) use it to check their ghost lists.
        policy.onMiss(key);
        return null;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(loader, "loader");

        V cached = getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        // MISS. If a thousand threads miss on this key at the same instant (a hot
        // entry just expired, say) only ONE of them runs the loader; the rest wait
        // and share its result. Without this, a cache turns one slow database row
        // into a thousand identical queries at the worst possible moment.
        //
        // Only the LEADER executes the function below, so only the leader stores the
        // value; followers just receive it. The loader itself runs here, outside any
        // manipulation of cache state, which is what will let Tier 2 call it without
        // holding a shard lock (a lock held across a slow database call would
        // serialise every request that maps to that shard).
        //
        // A loader that throws caches nothing: the failure goes to every waiting
        // caller, and the next caller starts a fresh attempt.
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
        Node<K, V> node = data.get(key, Hashing.spread(key));
        if (node == null) {
            return false;
        }
        // Deliberately does NOT call policy.onAccess or refresh any deadline:
        // asking whether something is cached is not the same as using it. It
        // also does not remove an expired entry -- a query should not mutate --
        // it just reports it as absent.
        return !(node.hasDeadline() && isExpired(node, ticker.read()));
    }

    // ------------------------------------------------------------------
    //  Writes
    // ------------------------------------------------------------------

    @Override
    public void put(K key, V value) {
        putInternal(key, value, -1);
        afterOperation();
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        putInternal(key, value, ttl.toNanos());
        afterOperation();
    }

    /**
     * @param explicitTtlNanos a per-entry TTL, or -1 to use the configured one
     */
    private void putInternal(K key, V value, long explicitTtlNanos) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");     // null would be indistinguishable from a miss

        // Weigh FIRST, before touching anything. A weigher that throws or returns
        // nonsense then leaves the cache exactly as it was.
        int weight = weigh(key, value);
        int hash = Hashing.spread(key);

        // Read the clock only if something about this call involves time.
        boolean sweep = expiry.size() > 0;
        boolean stamps = explicitTtlNanos >= 0 || expiryConfig.hasWriteTtl() || expiryConfig.hasAccessTtl();
        long now = (sweep || stamps) ? ticker.read() : 0;

        // Drain dead entries FIRST. Otherwise a corpse could occupy capacity
        // while a live entry is evicted to make room for the newcomer.
        if (sweep) {
            drainExpired(now);
        }

        // --- Case 1: the key is already cached. ---
        Node<K, V> existing = data.get(key, hash);
        if (existing != null) {
            int growth = weight - existing.weight();

            if (growth <= 0) {
                // Same size or lighter: update in place. No room is needed, and the
                // entry keeps its position and history in the policy.
                V previous = existing.value();
                existing.setValue(value);
                existing.setWeight(weight);
                weightedSize += growth;
                policy.onAccess(existing);           // a write counts as a use
                applyWriteDeadline(existing, now, explicitTtlNanos);
                if (previous != value) {
                    enqueue(key, previous, RemovalCause.REPLACED);   // the OLD value has left
                }
                return;
            }

            // HEAVIER: it needs more room than it has. Making room means asking
            // the policy for victims, and the policy may well nominate THIS entry
            // (it could be the LRU tail, the FIFO head, a random pick). Evicting the
            // very entry we are updating, mid-update, is a tangle we can avoid
            // entirely by taking it out first and inserting it afresh.
            //
            // The price: the entry forfeits its policy history (an LFU count, say).
            // That only happens on a write that grows the entry, and the old value
            // is being replaced anyway.
            data.remove(key, hash);
            detach(existing, RemovalCause.REPLACED);
        }

        // --- Case 2: a new entry (or one we just removed). Make room. ---

        if (weight > maximumWeight) {
            // It could not fit even in an empty cache. Do NOT evict anything for
            // it: flushing every useful entry to make room for something that still
            // will not fit would be pure loss. If this replaced an existing key, the
            // old value was removed above, so a stale value is never served.
            stats.recordRejection();
            enqueue(key, value, RemovalCause.SIZE);   // never stored, but the cache did not keep it
            return;
        }

        policy.beforeInsert(key);

        Node<K, V> candidate = new Node<>(key, value, hash);
        candidate.setWeight(weight);

        // Eviction is a LOOP: a large newcomer may need several small entries gone.
        while (weightedSize + weight > maximumWeight) {
            Node<K, V> victim = policy.selectVictim();
            if (victim == null) {
                stats.recordRejection();     // unreachable in practice: nothing left to evict
                enqueue(key, value, RemovalCause.SIZE);
                return;
            }

            // Admission control, checked against EVERY victim. LRU always says yes.
            // W-TinyLFU (Tier 3) may refuse the newcomer, which is how it survives
            // scans that take LRU's hit ratio to zero.
            //
            // A subtlety this loop introduces: if the candidate is refused on the
            // third victim, the first two are already gone. We accept that: the
            // alternative (working out the full victim set before evicting any) is
            // impossible for policies like CLOCK whose victim search mutates state.
            if (!policy.admit(candidate, victim)) {
                stats.recordRejection();
                enqueue(key, value, RemovalCause.SIZE);   // refused on the way in: still reported
                return;
            }
            policy.onEvict(victim);          // pushed out for lack of room, not deleted
            data.remove(victim.key(), victim.hash());
            detach(victim, RemovalCause.SIZE);
        }

        data.put(candidate);
        weightedSize += weight;
        policy.onInsert(candidate);
        applyWriteDeadline(candidate, now, explicitTtlNanos);
    }

    /**
     * @return this entry's weight, validated
     * @throws IllegalArgumentException if the weigher returns less than 1
     */
    private int weigh(K key, V value) {
        if (weigher == null) {
            return 1;                        // count-bounded: every entry weighs 1
        }
        int weight = weigher.weigh(key, value);
        if (weight < 1) {
            throw new IllegalArgumentException(
                    "a weigher must return at least 1, but returned " + weight + " for key " + key);
        }
        return weight;
    }

    @Override
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> removed = data.remove(key, Hashing.spread(key));
        if (removed != null) {
            detach(removed, RemovalCause.EXPLICIT);
        }
        afterOperation();
    }

    @Override
    public void invalidateAll() {
        // Everything is about to vanish in one sweep, bypassing detach(), so each entry
        // must be marked dead here (a buffered read record for it must be ignored later)
        // and, if anyone is listening, reported.
        data.forEach(node -> {
            node.markDead();
            enqueue(node.key(), node.value(), RemovalCause.EXPLICIT);
        });
        data.clear();
        policy.clear();
        expiry.clear();
        weightedSize = 0;
        afterOperation();
    }

    @Override
    public void cleanUp() {
        if (expiry.size() > 0) {
            drainExpired(ticker.read());
        }
        afterOperation();
    }

    // ------------------------------------------------------------------
    //  Removal -- the single path every entry leaves through
    // ------------------------------------------------------------------

    /**
     * Finishes removing {@code node}, which the caller has ALREADY taken out of
     * the hash map: gives its weight back, tells the policy, cancels its expiry,
     * and records why.
     *
     * <p>Every way an entry can leave -- evicted for space, invalidated, expired,
     * replaced by a heavier value -- ends here, so there is exactly one place that
     * keeps the structures and the running weight in step. The weight subtracted
     * is the one <i>recorded on the node when it was stored</i>, never a fresh
     * call to the weigher, so the total cannot drift.
     */
    private void detach(Node<K, V> node, RemovalCause cause) {
        node.markDead();                  // a buffered read record for it must now be ignored
        weightedSize -= node.weight();
        policy.onRemove(node);
        expiry.cancel(node);              // harmless if it was already polled out
        enqueue(node.key(), node.value(), cause);
        switch (cause) {
            case SIZE -> stats.recordEviction();
            case EXPIRED -> stats.recordExpiration();
            default -> { /* explicit and replaced removals are not symptoms of anything */ }
        }
    }

    // ------------------------------------------------------------------
    //  Removal notifications
    // ------------------------------------------------------------------

    /** Queues a notification. Does nothing (and allocates nothing) if nobody is listening. */
    private void enqueue(K key, V value, RemovalCause cause) {
        if (removalListener != null) {
            pendingRemovals.add(new Removal<>(key, value, cause));
        }
    }

    /**
     * Delivers queued notifications. Called at the END of each public operation,
     * when the cache is fully consistent, so a listener never sees (or is able to
     * disturb) a half-finished update.
     *
     * <p>If a listener calls back into the cache, that inner call queues its own
     * removals and returns straight away ({@code dispatching} is already set); this
     * loop then delivers them, in order, after the current listener has returned.
     * Without that guard the inner call would deliver its notifications
     * <i>inside</i> the outer listener, out of order and one stack frame deeper per
     * cascade.
     *
     * <p>A {@link RuntimeException} from a listener is logged and the loop carries
     * on: one broken listener call must not lose the other notifications or fail the
     * operation that triggered them. An {@link Error} escapes, but the
     * {@code finally} clears the flag and anything undelivered stays queued for the
     * next operation.
     */
    /** Delivers queued notifications now, unless this cache is a shard whose owner does it after unlocking. */
    private void afterOperation() {
        if (dispatchInline) {
            dispatchRemovals();
        }
    }

    /**
     * Delivers every queued notification. Safe to call from any thread, and harmless
     * if another thread is already delivering.
     *
     * <p>The loop re-checks the queue AFTER releasing the flag. Another thread may have
     * queued a notification just after our last poll; its own attempt to deliver failed
     * because we still held the flag. Without the re-check that notification would sit
     * in the queue until some unrelated later operation happened to flush it.
     */
    void dispatchRemovals() {
        while (!pendingRemovals.isEmpty() && dispatching.compareAndSet(false, true)) {
            try {
                Removal<K, V> removal;
                while ((removal = pendingRemovals.poll()) != null) {
                    try {
                        removalListener.onRemoval(removal.key(), removal.value(), removal.cause());
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING,
                                "removal listener threw for key " + removal.key() + " (" + removal.cause() + ")", e);
                    }
                }
            } finally {
                Runnable hook = beforeDeliveryFlagRelease;
                if (hook != null) {
                    hook.run();
                }
                dispatching.set(false);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Support for ShardedCache (package-private)
    // ------------------------------------------------------------------

    /**
     * A read-only lookup for the shared-lock read path.
     *
     * @return the entry if it is present and has not expired; {@code null} otherwise.
     *         Changes NOTHING: no statistics, no policy update, no lazy removal
     */
    Node<K, V> findLive(K key, int hash) {
        Node<K, V> node = data.get(key, hash);
        if (node == null) {
            return null;
        }
        if (node.hasDeadline() && isExpired(node, ticker.read())) {
            return null;                  // the exclusive slow path removes it and reports it
        }
        return node;
    }

    /** Counts a hit that was served on the shared-lock path. Thread-safe. */
    void recordHit() {
        stats.recordHit();
    }

    /**
     * Applies a buffered "this entry was used" record to the policy. Must be called
     * under the shard write lock. Ignores entries that left the cache since the read.
     */
    void applyBufferedAccess(Node<K, V> node) {
        if (!node.isDead()) {
            policy.onAccess(node);
        }
    }

    /** @return whether every read must update the expiry structure (expire-after-access) */
    boolean expiresOnAccess() {
        return expiryConfig.hasAccessTtl();
    }

    /** Visits every entry, in unspecified order. For invariant checks; the caller must hold the shard lock. */
    void forEachNode(java.util.function.Consumer<Node<K, V>> action) {
        data.forEach(action);
    }

    /** Removes every entry the expiry engine reports as due. */
    private void drainExpired(long now) {
        Node<K, V> due;
        while ((due = expiry.pollExpired(now)) != null) {
            data.remove(due.key(), due.hash());
            detach(due, RemovalCause.EXPIRED);
        }
    }

    // ------------------------------------------------------------------
    //  Deadlines
    // ------------------------------------------------------------------

    /** Whether {@code node} is past its deadline. Subtraction keeps this correct across a clock wrap. */
    private static boolean isExpired(Node<?, ?> node, long now) {
        return now - node.expiresAtNanos() >= 0;
    }

    /**
     * Sets the deadline that a WRITE establishes: the configured expire-after-write,
     * or an explicit per-entry TTL, or none.
     */
    private void applyWriteDeadline(Node<K, V> node, long now, long explicitTtlNanos) {
        long ttl = explicitTtlNanos >= 0 ? explicitTtlNanos : expiryConfig.expireAfterWriteNanos();

        if (ttl >= 0) {
            node.setHardDeadlineNanos(now + jittered(ttl));
        } else {
            node.clearHardDeadline();
        }
        recomputeDeadline(node, now);
    }

    /**
     * Derives the effective deadline as the EARLIER of the write-based hard
     * deadline and (if configured) now + the idle timeout, then makes the
     * expiry engine agree.
     */
    private void recomputeDeadline(Node<K, V> node, long now) {
        boolean has = node.hasHardDeadline();
        long deadline = node.hardDeadlineNanos();

        if (expiryConfig.hasAccessTtl()) {
            long idle = now + jittered(expiryConfig.expireAfterAccessNanos());
            if (!has || idle - deadline < 0) {
                deadline = idle;
            }
            has = true;
        }

        if (has) {
            node.setExpiresAtNanos(deadline);
            expiry.schedule(node);
        } else {
            node.clearDeadline();
            expiry.cancel(node);
        }
    }

    /**
     * Randomises a TTL by up to +/- the configured jitter, so that entries
     * written together do not all expire together (a cache avalanche).
     */
    private long jittered(long ttlNanos) {
        double jitter = expiryConfig.jitter();
        if (jitter == 0) {
            return ttlNanos;
        }
        double factor = 1 + jitter * (2 * jitterRandom.nextDouble() - 1);
        return Math.max(1, (long) (ttlNanos * factor));
    }

    // ------------------------------------------------------------------
    //  Introspection
    // ------------------------------------------------------------------

    @Override
    public int size() {
        return data.size();
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
    public long weightedSize() {
        return weightedSize;
    }

    @Override
    public CacheStats stats() {
        // The cache's own counters plus the loader counters kept by SingleFlight.
        return stats.snapshot().withLoadCounts(flight.loads(), flight.failures(), flight.coalesced());
    }

    /** @return the name of the active eviction policy, e.g. {@code "LRU"} */
    public String policyName() {
        return policy.name();
    }

    /** @return the name of the active expiry engine, e.g. {@code "INDEXED_HEAP"} */
    public String expiryEngineName() {
        return expiry.name();
    }

    /**
     * Verifies the cache is internally consistent. Does nothing unless
     * {@code -Dvelox.assertions=true}.
     *
     * @throws IllegalStateException if the cache is in an impossible state
     */
    public void assertInvariants() {
        if (!Invariants.ENABLED) {
            return;
        }
        Invariants.check(weightedSize <= maximumWeight,
                "cache holds weight " + weightedSize + ", over its budget of " + maximumWeight);
        if (maximumEntries >= 0) {
            Invariants.check(data.size() <= maximumEntries,
                    "cache holds " + data.size() + " entries, over its limit of " + maximumEntries);
        }
        data.assertInvariants();
        // This compares the policy's entry count against the map's, which is
        // what catches the two structures drifting apart.
        policy.assertInvariants(data.size());
        expiry.assertInvariants();

        // Every entry with a deadline must be scheduled, and nothing else may be.
        // And the running weight must equal the sum of the entries' recorded
        // weights: the check that catches a removal path that forgot to give
        // its weight back.
        int[] withDeadline = {0};
        long[] totalWeight = {0};
        data.forEach(node -> {
            Invariants.check(node.weight() >= 1, "entry " + node.key() + " has weight " + node.weight());
            totalWeight[0] += node.weight();
            Invariants.check(node.hasDeadline() == expiry.isScheduled(node),
                    "entry " + node.key() + " hasDeadline=" + node.hasDeadline()
                            + " but the expiry engine disagrees");
            if (node.hasDeadline()) {
                withDeadline[0]++;
            }
        });
        Invariants.check(totalWeight[0] == weightedSize,
                "the entries weigh " + totalWeight[0] + " in total but the running weight says " + weightedSize
                        + " -- a removal path forgot to give its weight back");
        Invariants.check(withDeadline[0] == expiry.size(),
                "the expiry engine tracks " + expiry.size() + " entries but " + withDeadline[0]
                        + " cached entries have deadlines -- an entry was removed without being cancelled");
    }

    @Override
    public String toString() {
        String bound = maximumEntries >= 0
                ? size() + "/" + maximumEntries + " entries"
                : weightedSize + "/" + maximumWeight + " weight (" + size() + " entries)";
        return "VeloxCache[" + policy.name() + ", " + bound + ", " + stats() + "]";
    }
}
