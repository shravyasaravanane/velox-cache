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

    private int maximumSize = 1_000;
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
        EvictionPolicy<K, V> evictionPolicy = policy.create(maximumSize);
        ExpiryConfig expiry = new ExpiryConfig(expireAfterWriteNanos, expireAfterAccessNanos, ttlJitter);
        ExpiryEngine<K, V> engine = switch (expiryEngineType) {
            case INDEXED_HEAP -> new HeapExpiryEngine<>();
            // The wheel counts time from its creation, so it starts at "now".
            case TIMING_WHEEL -> new WheelExpiryEngine<>(wheelTickNanos, wheelSize, ticker.read());
        };
        return new VeloxCache<>(maximumSize, evictionPolicy, expiry, ticker, engine);
    }

    private static long positiveNanos(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + duration);
        }
        return duration.toNanos();
    }
}
