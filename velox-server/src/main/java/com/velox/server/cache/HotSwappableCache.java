package com.velox.server.cache;

import com.velox.core.Cache;
import com.velox.core.CacheBuilder;
import com.velox.core.ShardedCache;
import com.velox.core.policy.Policy;
import com.velox.core.stats.CacheStats;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A {@link Cache} that can be rebuilt live, in place, without callers ever re-fetching a
 * reference to it. This is what {@code POST /api/admin/policy/{name}} and
 * {@code POST /api/admin/capacity/{n}} actually do: build a brand-new {@link Cache} with the
 * requested policy or capacity and atomically swap it in behind this same object.
 *
 * <h2>What a swap does to the entries already held</h2>
 *
 * A swap starts the new cache empty. There is no way to replay one policy's internal state --
 * recency lists, ghost lists, frequency sketches -- into a differently-shaped policy, so an
 * in-place migration is not attempted: the traffic right after a swap is a genuine cold start.
 * The live demo is honest about that; watching the hit rate visibly drop to zero and climb back
 * up is itself the point of a policy hot-swap demo, not a bug in it.
 *
 * <p>A request in flight during the swap sees either the old cache or the new one, never a
 * partial mix of both -- every method reads {@link #delegate} exactly once via a single atomic
 * get.
 *
 * <h2>Why every rebuild passes {@code concurrencyLevel}</h2>
 *
 * {@link CacheBuilder#build()} returns a plain, single-threaded {@code VeloxCache} unless
 * {@link CacheBuilder#concurrencyLevel(int)} is set -- a bare {@code .build()} is safe only
 * from one thread at a time. A Spring MVC servlet container calls this cache from many request
 * threads at once, so every cache built here is a {@code ShardedCache}, never the bare default.
 * This was a real bug, not a hypothetical: caught by {@code LoadGenerator} throwing intrusive
 * linked-list {@code NullPointerException}s under concurrent HTTP traffic before this class
 * passed a concurrency level at all.
 */
public final class HotSwappableCache<K, V> implements Cache<K, V> {

    private final AtomicReference<Cache<K, V>> delegate;
    private final int concurrencyLevel;
    private volatile int capacity;
    private volatile Policy policy;

    public HotSwappableCache(int capacity, Policy policy, int concurrencyLevel) {
        this.capacity = capacity;
        this.policy = policy;
        this.concurrencyLevel = concurrencyLevel;
        this.delegate = new AtomicReference<>(build(capacity, policy, concurrencyLevel));
    }

    private static <K, V> Cache<K, V> build(int capacity, Policy policy, int concurrencyLevel) {
        return CacheBuilder.<K, V>newBuilder()
                .maximumSize(capacity)
                .policy(policy)
                .concurrencyLevel(concurrencyLevel)
                .build();
    }

    /** Rebuilds the cache empty, with a new eviction policy at the current capacity. */
    public synchronized void swapPolicy(Policy newPolicy) {
        this.policy = newPolicy;
        replace(build(capacity, newPolicy, concurrencyLevel));
    }

    /** Rebuilds the cache empty, at a new capacity with the current policy. */
    public synchronized void resize(int newCapacity) {
        this.capacity = newCapacity;
        replace(build(newCapacity, policy, concurrencyLevel));
    }

    private void replace(Cache<K, V> fresh) {
        Cache<K, V> old = delegate.getAndSet(fresh);
        old.close();
    }

    public Policy currentPolicy() {
        return policy;
    }

    public int currentCapacity() {
        return capacity;
    }

    /**
     * @return the current entry count of each shard, in shard order, for the live dashboard's
     *         per-shard load panel; empty if the current delegate is not a {@link ShardedCache}
     *         (it always is in this application, since {@link CacheConfig} always passes a
     *         {@code concurrencyLevel} -- see that class' Javadoc for why)
     */
    public int[] shardSizes() {
        Cache<K, V> current = delegate.get();
        return current instanceof ShardedCache<K, V> sharded ? sharded.shardSizes() : new int[0];
    }

    // ------------------------------------------------------------------
    //  Cache<K, V>: every method forwarded to whichever cache is current
    // ------------------------------------------------------------------

    @Override
    public V getIfPresent(K key) {
        return delegate.get().getIfPresent(key);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        return delegate.get().get(key, loader);
    }

    @Override
    public void put(K key, V value) {
        delegate.get().put(key, value);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        delegate.get().put(key, value, ttl);
    }

    @Override
    public void cleanUp() {
        delegate.get().cleanUp();
    }

    @Override
    public void invalidate(K key) {
        delegate.get().invalidate(key);
    }

    @Override
    public void invalidateAll() {
        delegate.get().invalidateAll();
    }

    @Override
    public boolean containsKey(K key) {
        return delegate.get().containsKey(key);
    }

    @Override
    public int size() {
        return delegate.get().size();
    }

    @Override
    public int maximumSize() {
        return delegate.get().maximumSize();
    }

    @Override
    public long maximumWeight() {
        return delegate.get().maximumWeight();
    }

    @Override
    public long weightedSize() {
        return delegate.get().weightedSize();
    }

    @Override
    public CacheStats stats() {
        return delegate.get().stats();
    }

    @Override
    public void close() {
        delegate.get().close();
    }
}
