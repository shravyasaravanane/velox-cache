package com.velox.core;

import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.ExpiryEngine;
import com.velox.core.expiry.HeapExpiryEngine;
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
import java.util.function.Function;

/**
 * The cache engine: a bounded map with a pluggable eviction policy and
 * optional time-based expiry.
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
 * <b>every removal must update all three</b>. That is why eviction,
 * invalidation and expiry all funnel through one method, {@link #detach}.
 * Removing from two and forgetting the third leaks memory or leaves a phantom
 * entry -- exactly what {@link #assertInvariants()} checks for.
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
 *       due entries from the expiry engine. This matters for capacity: without
 *       it, a dead entry would sit in the cache while a perfectly live one was
 *       evicted to make room.</li>
 *   <li><b>On demand</b> via {@link #cleanUp()}.</li>
 * </ol>
 *
 * A cache that is neither read nor written keeps its expired entries in
 * memory until one of those happens. That is the price of having no thread.
 *
 * <h2>The clock is only read when needed</h2>
 *
 * Reading the clock costs real time (tens of nanoseconds), so a cache with no
 * TTLs configured never reads it: reads and writes skip the clock entirely
 * unless expiry is actually in play.
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

    /** Hard limit on entries. */
    private final int maximumSize;

    private final StatsCounter stats = new StatsCounter();

    /**
     * Creates a cache with no configured expiry. Entries still expire if given
     * a per-entry TTL through {@link #put(Object, Object, Duration)}.
     *
     * @param maximumSize the most entries to hold; must be at least 1
     * @param policy      the eviction strategy
     */
    public VeloxCache(int maximumSize, EvictionPolicy<K, V> policy) {
        this(maximumSize, policy, ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>());
    }

    /**
     * @param maximumSize  the most entries to hold; must be at least 1
     * @param policy       the eviction strategy
     * @param expiryConfig how entries expire
     * @param ticker       the time source; tests pass a fake one
     * @param expiry       the engine that schedules expiry
     */
    public VeloxCache(int maximumSize, EvictionPolicy<K, V> policy, ExpiryConfig expiryConfig,
                      Ticker ticker, ExpiryEngine<K, V> expiry) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be at least 1, got " + maximumSize);
        }
        this.maximumSize = maximumSize;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.expiryConfig = Objects.requireNonNull(expiryConfig, "expiryConfig");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.expiry = Objects.requireNonNull(expiry, "expiry");
        // Size the table for the full capacity up front. The cache will run at
        // its limit essentially forever, so pre-sizing avoids resizing later.
        this.data = new OpenAddressingMap<>(maximumSize);
    }

    // ------------------------------------------------------------------
    //  Reads
    // ------------------------------------------------------------------

    @Override
    public V getIfPresent(K key) {
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
                // This is why access-based TTL is costlier -- every hit repositions
                // the entry in the expiry structure, O(log n) with the heap.
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

        // MISS: compute the value and cache it.
        //
        // Note what is missing here. If a thousand threads miss on the same
        // hot key at the same instant, this runs the loader a thousand times
        // for one database row -- a "cache stampede", and a classic way a
        // caching layer takes down the system it was meant to protect.
        // A later step adds single-flight coalescing so only one loader runs
        // and the rest wait for its result.
        stats.recordLoad();
        V loaded = loader.apply(key);
        if (loaded != null) {
            put(key, loaded);
        }
        return loaded;
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
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        putInternal(key, value, ttl.toNanos());
    }

    /**
     * @param explicitTtlNanos a per-entry TTL, or -1 to use the configured one
     */
    private void putInternal(K key, V value, long explicitTtlNanos) {
        Objects.requireNonNull(key, "key");
        int hash = Hashing.spread(key);

        // Read the clock only if something about this call involves time.
        boolean sweep = expiry.size() > 0;
        boolean stamps = explicitTtlNanos >= 0 || expiryConfig.hasWriteTtl() || expiryConfig.hasAccessTtl();
        long now = (sweep || stamps) ? ticker.read() : 0;

        // Drain dead entries FIRST. Otherwise a corpse could occupy a slot
        // while a live entry is evicted to make room for the newcomer.
        if (sweep) {
            drainExpired(now);
        }

        // --- Case 1: the key is already cached. Update in place. ---
        Node<K, V> existing = data.get(key, hash);
        if (existing != null) {
            existing.setValue(value);
            policy.onAccess(existing);           // a write counts as a use
            applyWriteDeadline(existing, now, explicitTtlNanos);
            return;
        }

        // --- Case 2: a new key. We may need to make room first. ---
        Node<K, V> candidate = new Node<>(key, value, hash);

        if (data.size() >= maximumSize) {
            Node<K, V> victim = policy.selectVictim();

            if (victim != null) {
                // Admission control. LRU always says yes. W-TinyLFU (Tier 3)
                // compares the two entries' estimated frequencies and may
                // refuse the newcomer outright -- which is how it survives
                // scans that take LRU's hit ratio to zero.
                if (!policy.admit(candidate, victim)) {
                    stats.recordRejection();
                    return;              // candidate discarded; cache unchanged
                }
                data.remove(victim.key(), victim.hash());
                detach(victim, RemovalCause.SIZE);
            }
        }

        data.put(candidate);
        policy.onInsert(candidate);
        applyWriteDeadline(candidate, now, explicitTtlNanos);
    }

    @Override
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> removed = data.remove(key, Hashing.spread(key));
        if (removed != null) {
            detach(removed, RemovalCause.EXPLICIT);
        }
    }

    @Override
    public void invalidateAll() {
        data.clear();
        policy.clear();
        expiry.clear();
    }

    @Override
    public void cleanUp() {
        if (expiry.size() > 0) {
            drainExpired(ticker.read());
        }
    }

    // ------------------------------------------------------------------
    //  Removal -- the single path every entry leaves through
    // ------------------------------------------------------------------

    /**
     * Finishes removing {@code node}, which the caller has ALREADY taken out of
     * the hash map: tells the policy, cancels its expiry, and records why.
     *
     * <p>Every way an entry can leave -- evicted for space, invalidated, expired
     * -- ends here, so there is exactly one place that keeps the three
     * structures in step. Two callers forgetting one of the three updates in
     * two slightly different ways is precisely how such caches leak.
     */
    private void detach(Node<K, V> node, RemovalCause cause) {
        policy.onRemove(node);
        expiry.cancel(node);              // harmless if it was already polled out
        switch (cause) {
            case SIZE -> stats.recordEviction();
            case EXPIRED -> stats.recordExpiration();
            default -> { /* explicit and replaced removals are not symptoms of anything */ }
        }
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
     *
     * <p>Taking the earlier of the two is what lets both TTL kinds coexist:
     * reads keep an entry alive by pushing the access deadline out, but can
     * never push it past the hard limit a write set.
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
        return maximumSize;
    }

    @Override
    public CacheStats stats() {
        return stats.snapshot();
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
        Invariants.check(data.size() <= maximumSize,
                "cache holds " + data.size() + " entries, over its limit of " + maximumSize);
        data.assertInvariants();
        // This compares the policy's entry count against the map's, which is
        // what catches the two structures drifting apart.
        policy.assertInvariants(data.size());
        expiry.assertInvariants();

        // Every entry with a deadline must be scheduled, and nothing else may be.
        int[] withDeadline = {0};
        data.forEach(node -> {
            Invariants.check(node.hasDeadline() == expiry.isScheduled(node),
                    "entry " + node.key() + " hasDeadline=" + node.hasDeadline()
                            + " but the expiry engine disagrees");
            if (node.hasDeadline()) {
                withDeadline[0]++;
            }
        });
        Invariants.check(withDeadline[0] == expiry.size(),
                "the expiry engine tracks " + expiry.size() + " entries but " + withDeadline[0]
                        + " cached entries have deadlines -- an entry was removed without being cancelled");
    }

    @Override
    public String toString() {
        return "VeloxCache[" + policy.name() + ", " + size() + "/" + maximumSize + ", " + stats() + "]";
    }
}
