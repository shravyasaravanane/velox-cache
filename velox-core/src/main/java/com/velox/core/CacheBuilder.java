package com.velox.core;

import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.ExpiryEngine;
import com.velox.core.expiry.ExpiryEngineType;
import com.velox.core.expiry.HeapExpiryEngine;
import com.velox.core.expiry.WheelExpiryEngine;
import com.velox.core.policy.EvictionPolicy;
import com.velox.core.policy.Policy;
import com.velox.core.util.Ticker;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Fluent builder for {@link Cache} instances.
 *
 * <pre>{@code
 * Cache<Long, Product> cache = CacheBuilder.<Long, Product>newBuilder()
 *         .maximumSize(100_000)
 *         .policy(Policy.LRU)
 *         .expireAfterWrite(Duration.ofMinutes(10))
 *         .ttlJitter(0.15)
 *         .build();
 * }</pre>
 *
 * <h2>Why a builder rather than constructors</h2>
 *
 * By the end of this project a cache will be configurable with a maximum
 * size, a maximum weight in bytes, an eviction policy, two kinds of
 * expiry, TTL jitter, refresh-ahead, a shard count, a removal listener and a
 * loader. As constructor parameters that is unusable -- a dozen arguments,
 * most of them optional, several the same type and therefore easy to swap by
 * accident.
 *
 * <p>A builder gives each option a name, lets callers set only what they
 * care about, and keeps the API additive: new options in later tiers are new
 * methods, and no existing code breaks.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class CacheBuilder<K, V> {

    private int maximumSize = -1;                // -1 = not set
    private long maximumWeight = -1;             // -1 = not set
    private Weigher<K, V> weigher;
    private int expectedEntries = -1;
    private RemovalListener<K, V> removalListener;
    private int concurrencyLevel = -1;             // -1 = single-threaded engine
    private boolean bufferedReads = true;
    private int readBufferSize = 64;
    private Policy policy = Policy.LRU;
    private long expireAfterWriteNanos = -1;
    private long expireAfterAccessNanos = -1;
    private double ttlJitter = 0;
    private Ticker ticker = Ticker.system();
    private ExpiryEngineType expiryEngineType = ExpiryEngineType.INDEXED_HEAP;
    private long wheelTickNanos = 1_000_000;      // 1 ms
    private int wheelSize = 64;

    private CacheBuilder() {
    }

    /**
     * @param <K> the key type
     * @param <V> the value type
     * @return a new builder with defaults: 1,000 entries, LRU, no expiry
     */
    public static <K, V> CacheBuilder<K, V> newBuilder() {
        return new CacheBuilder<>();
    }

    /**
     * Sets the maximum number of entries.
     *
     * @param maximumSize the entry limit; must be at least 1
     * @return this builder
     */
    public CacheBuilder<K, V> maximumSize(int maximumSize) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be at least 1, got " + maximumSize);
        }
        this.maximumSize = maximumSize;
        return this;
    }

    /**
     * Bounds the cache by total <b>weight</b> instead of entry count, so that
     * capacity can mean bytes rather than "number of things". Requires a
     * {@link #weigher}, and cannot be combined with {@link #maximumSize}.
     *
     * @param maximumWeight the budget, in whatever unit the weigher returns; at least 1
     * @return this builder
     */
    public CacheBuilder<K, V> maximumWeight(long maximumWeight) {
        if (maximumWeight < 1) {
            throw new IllegalArgumentException("maximumWeight must be at least 1, got " + maximumWeight);
        }
        this.maximumWeight = maximumWeight;
        return this;
    }

    /**
     * Says how much of the budget each entry uses. Only meaningful together
     * with {@link #maximumWeight}.
     *
     * @param weigher must return at least 1, and the same answer every time for a given key and value
     * @return this builder
     */
    public CacheBuilder<K, V> weigher(Weigher<K, V> weigher) {
        this.weigher = Objects.requireNonNull(weigher, "weigher");
        return this;
    }

    /**
     * A rough guess at how many entries a weight-bounded cache will hold, used
     * to pre-size the hash table and to size policies that depend on capacity
     * (such as LFU aging). A weight budget does not fix an entry count, so the
     * cache cannot work this out itself. The cache still works if the guess is
     * wrong; it just sizes things less well. Ignored for count-bounded caches.
     *
     * @param expectedEntries roughly how many entries to expect; at least 1
     * @return this builder
     */
    public CacheBuilder<K, V> expectedEntries(int expectedEntries) {
        if (expectedEntries < 1) {
            throw new IllegalArgumentException("expectedEntries must be at least 1, got " + expectedEntries);
        }
        this.expectedEntries = expectedEntries;
        return this;
    }

    /**
     * Chooses the eviction policy.
     *
     * @param policy which algorithm decides what to evict
     * @return this builder
     */
    public CacheBuilder<K, V> policy(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * Entries expire this long after they were written, however often they are read.
     *
     * <p>This bounds <b>staleness</b>: the longest a changed database row can go
     * unnoticed. Reach for this one first.
     *
     * @param duration the time to live; must be positive
     * @return this builder
     */
    public CacheBuilder<K, V> expireAfterWrite(Duration duration) {
        this.expireAfterWriteNanos = positiveNanos(duration, "expireAfterWrite");
        return this;
    }

    /**
     * Entries expire this long after they were last read or written.
     *
     * <p>This bounds <b>memory held by idle data</b>. On its own it lets a
     * popular entry live forever and go arbitrarily stale, so pair it with
     * {@link #expireAfterWrite} when freshness matters. Costlier than
     * expire-after-write: every hit must reposition the entry in the expiry
     * structure.
     *
     * @param duration the idle timeout; must be positive
     * @return this builder
     */
    public CacheBuilder<K, V> expireAfterAccess(Duration duration) {
        this.expireAfterAccessNanos = positiveNanos(duration, "expireAfterAccess");
        return this;
    }

    /**
     * Randomises every TTL by up to +/- this fraction, so entries written
     * together do not all expire together (a cache avalanche).
     *
     * @param fraction in [0, 1); 0.15 means each TTL varies by up to 15%
     * @return this builder
     */
    public CacheBuilder<K, V> ttlJitter(double fraction) {
        if (fraction < 0 || fraction >= 1) {
            throw new IllegalArgumentException("ttlJitter must be in [0, 1), got " + fraction);
        }
        this.ttlJitter = fraction;
        return this;
    }

    /**
     * Chooses how expiry is scheduled: an indexed heap (the default) or a
     * hierarchical timing wheel with 1 ms ticks and 64 slots per level.
     *
     * @param type the scheduling strategy
     * @return this builder
     */
    public CacheBuilder<K, V> expiryEngine(ExpiryEngineType type) {
        this.expiryEngineType = Objects.requireNonNull(type, "type");
        return this;
    }

    /**
     * Uses a hierarchical timing wheel for expiry, with the given geometry.
     *
     * <p>A finer tick means less scanning of the current slot but more levels
     * to cascade through; a larger wheel does the reverse. The defaults (1 ms,
     * 64 slots) suit TTLs from milliseconds to hours.
     *
     * @param tick      the width of the finest slot; must be at least 1 ns
     * @param wheelSize slots per level; a power of two, at least 2
     * @return this builder
     */
    public CacheBuilder<K, V> timingWheel(Duration tick, int wheelSize) {
        this.wheelTickNanos = positiveNanos(tick, "tick");
        if (wheelSize < 2 || Integer.bitCount(wheelSize) != 1) {
            throw new IllegalArgumentException("wheelSize must be a power of two >= 2, got " + wheelSize);
        }
        this.wheelSize = wheelSize;
        this.expiryEngineType = ExpiryEngineType.TIMING_WHEEL;
        return this;
    }

    /**
     * Makes the cache <b>thread-safe</b> by splitting it into independent shards, each
     * with its own lock. Without this call {@link #build()} returns the single-threaded
     * engine, which must not be shared between threads.
     *
     * <p>More shards mean less contention, and each shard holds a proportionally smaller
     * share of the capacity. A good starting point is a few times the number of threads
     * that will use the cache. See {@link ShardedCache} for the trade-offs.
     *
     * @param shards the desired shard count, at least 1; rounded up to a power of two and
     *               reduced if the capacity is too small to give every shard a share
     * @return this builder
     */
    public CacheBuilder<K, V> concurrencyLevel(int shards) {
        if (shards < 1) {
            throw new IllegalArgumentException("concurrencyLevel must be at least 1, got " + shards);
        }
        this.concurrencyLevel = shards;
        return this;
    }

    /**
     * Whether hits in a sharded cache take only the shared lock and defer the recency
     * update through a lock-free buffer (the default), or take the exclusive lock like
     * every other operation. Turning it off exists so a benchmark can measure how much
     * the buffering is worth; it has no effect without {@link #concurrencyLevel}.
     *
     * @param enabled {@code true} for buffered reads
     * @return this builder
     */
    public CacheBuilder<K, V> bufferedReads(boolean enabled) {
        this.bufferedReads = enabled;
        return this;
    }

    /**
     * Slots in each shard's read buffer. A larger buffer drops fewer reads under bursts
     * and drains less often; a smaller one uses less memory.
     *
     * @param slots a power of two, at least 2
     * @return this builder
     */
    public CacheBuilder<K, V> readBufferSize(int slots) {
        if (slots < 2 || Integer.bitCount(slots) != 1) {
            throw new IllegalArgumentException("readBufferSize must be a power of two >= 2, got " + slots);
        }
        this.readBufferSize = slots;
        return this;
    }

    /**
     * Registers a listener that is told whenever a value leaves the cache, and why.
     * See {@link RemovalListener} for exactly what is reported, when, and what the
     * listener may do.
     *
     * @param listener called for every removal; must not be {@code null}
     * @return this builder
     */
    public CacheBuilder<K, V> removalListener(RemovalListener<K, V> listener) {
        this.removalListener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Replaces the time source. Intended for tests, which pass a fake clock so
     * expiry can be checked exactly without waiting.
     *
     * @param ticker the time source
     * @return this builder
     */
    public CacheBuilder<K, V> ticker(Ticker ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        return this;
    }

    /**
     * @return a new cache with the configured settings
     */
    public Cache<K, V> build() {
        Capacity<K, V> capacity = buildCapacity();
        ExpiryConfig expiry = new ExpiryConfig(expireAfterWriteNanos, expireAfterAccessNanos, ttlJitter);

        // The wheel counts time from its creation, so it starts at "now".
        final long wheelStart = expiryEngineType == ExpiryEngineType.TIMING_WHEEL ? ticker.read() : 0;
        Supplier<ExpiryEngine<K, V>> expiryFactory = () -> switch (expiryEngineType) {
            case INDEXED_HEAP -> new HeapExpiryEngine<>();
            case TIMING_WHEEL -> new WheelExpiryEngine<>(wheelTickNanos, wheelSize, wheelStart);
        };

        if (concurrencyLevel >= 1) {
            return new ShardedCache<>(capacity, concurrencyLevel, hint -> policy.create(hint), expiryFactory,
                    expiry, ticker, removalListener, bufferedReads, readBufferSize);
        }
        EvictionPolicy<K, V> evictionPolicy = policy.create(capacity.sizingHint());
        return new VeloxCache<>(capacity, evictionPolicy, expiry, ticker, expiryFactory.get(), removalListener);
    }

    /**
     * Resolves the size options into one capacity, rejecting contradictory ones
     * rather than silently picking a winner.
     */
    private Capacity<K, V> buildCapacity() {
        if (maximumWeight >= 0) {
            if (maximumSize >= 0) {
                throw new IllegalStateException(
                        "maximumSize and maximumWeight are mutually exclusive: a cache is bounded by one or the other");
            }
            if (weigher == null) {
                throw new IllegalStateException("maximumWeight requires a weigher to say what each entry weighs");
            }
            // A weight budget does not fix an entry count, so take the caller's guess
            // if given. Otherwise assume entries weigh more than 1, which keeps the
            // hint from being absurd for a byte budget: cap it at something sensible.
            int hint = expectedEntries > 0 ? expectedEntries : (int) Math.min(maximumWeight, 65_536);
            return Capacity.weighted(maximumWeight, weigher, hint);
        }
        if (weigher != null) {
            throw new IllegalStateException("a weigher only makes sense with maximumWeight");
        }
        return Capacity.entries(maximumSize >= 0 ? maximumSize : 1_000);
    }

    private static long positiveNanos(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + duration);
        }
        return duration.toNanos();
    }
}
