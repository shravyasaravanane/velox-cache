package com.velox.core;

import com.velox.core.policy.EvictionPolicy;
import com.velox.core.policy.Policy;

import java.util.Objects;

/**
 * Fluent builder for {@link Cache} instances.
 *
 * <pre>{@code
 * Cache<Long, Product> cache = CacheBuilder.<Long, Product>newBuilder()
 *         .maximumSize(100_000)
 *         .policy(Policy.LRU)
 *         .build();
 * }</pre>
 *
 * <h2>Why a builder rather than constructors</h2>
 *
 * By the end of this project a cache will be configurable with a maximum
 * size, a maximum weight in bytes, an eviction policy, two kinds of
 * expiry, TTL jitter, refresh-ahead, a shard count, a removal listener and a
 * loader. As constructor parameters that is unusable — a dozen arguments,
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

    private CacheBuilder() {
    }

    /**
     * @param <K> the key type
     * @param <V> the value type
     * @return a new builder with defaults: 1,000 entries, LRU
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
     * @return a new cache with the configured settings
     */
    public Cache<K, V> build() {
        EvictionPolicy<K, V> evictionPolicy = policy.create(maximumSize);
        return new VeloxCache<>(maximumSize, evictionPolicy);
    }
}
