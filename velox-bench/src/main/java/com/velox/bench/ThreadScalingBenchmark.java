package com.velox.bench;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.velox.bench.workload.AliasSampler;
import com.velox.core.Cache;
import com.velox.core.CacheBuilder;
import com.velox.core.ShardedCache;
import com.velox.core.VeloxCache;
import com.velox.core.policy.LruPolicy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * How does throughput change as threads are added?
 *
 * <h2>What this measures</h2>
 *
 * A web server's cache is hit by many threads at once, so "constant time" has to hold
 * when sixteen threads call {@code get} together, not just when one does. This runs the
 * cache-aside pattern (look up; on a miss, store) and reports <b>total operations per
 * millisecond across all threads</b>. Run it at 1, 2, 4, 8 and 16 threads with JMH's
 * {@code -t}. A cache that scaled perfectly would double its throughput each time the
 * threads doubled.
 *
 * <h2>The workload</h2>
 *
 * <ul>
 *   <li>Keys follow a <b>Zipf(0.99)</b> distribution over 100,000 keys: a few keys receive
 *       most requests, as in real web traffic.</li>
 *   <li><b>Every thread draws its own independent stream</b> (one generator per thread,
 *       O(1) sampling by the alias method). An earlier version had all threads walk one
 *       shared fixed sample from different offsets; threads that started close together
 *       then re-requested each other's keys, inflating the hit ratio by an amount that
 *       varied from run to run, which made rows incomparable.</li>
 *   <li>{@code capacityPercent} sets the cache size as a share of the key space, giving a
 *       get-heavy / mixed / write-heavy spread across three points. <b>1</b> gives a hit
 *       ratio around 15-20% (write-dominated: most requests miss, and every miss is a
 *       write). <b>10</b> gives around 70% (a miss every ~3 requests, a genuine mix).
 *       <b>50</b> gives around 90-95%, a read-heavy workload of the kind caches are
 *       actually deployed for, and the case buffered reads are designed for.</li>
 * </ul>
 *
 * <h2>The implementations</h2>
 *
 * <ul>
 *   <li>{@code SYNC_LINKED_HASH_MAP}: the textbook Java LRU, an access-ordered
 *       {@code LinkedHashMap} behind {@code Collections.synchronizedMap}.</li>
 *   <li>{@code VELOX_GLOBAL_LOCK}: our single-threaded engine wrapped in one lock.</li>
 *   <li>{@code VELOX_SHARDED_EXCLUSIVE}: sharded, but every read still takes the shard's
 *       exclusive lock. Shows what <i>sharding alone</i> buys.</li>
 *   <li>{@code VELOX_SHARDED_BUFFERED}: sharded, and hits take only the shared lock and
 *       defer the recency update through the lossy read buffer. The full design.</li>
 *   <li>{@code CAFFEINE}: the industry-standard JVM cache, as a yardstick. Mature and
 *       heavily tuned; and it uses a smarter admission policy (TinyLFU), so it reaches a
 *       higher hit ratio than the LRU caches here. The honest goal is its neighbourhood.</li>
 *   <li>{@code GUAVA}: an older, widely-deployed cache, included alongside Caffeine as a
 *       second, less aggressively tuned reference point -- Caffeine's own authors wrote it
 *       as Guava's eventual replacement, so the gap between the two is itself informative.</li>
 *   <li>{@code CONCURRENT_HASH_MAP}: no eviction, no ordering. Not a cache at all (it grows
 *       to hold every key): a ceiling showing what a hash map can do when it is not also
 *       maintaining eviction order.</li>
 * </ul>
 *
 * <h2>Reading the results honestly</h2>
 *
 * <b>Throughput depends on hit ratio</b>, because every miss triggers a write. The
 * benchmark therefore prints each thread's hit and request counts ({@code [thread-stats]})
 * so hit ratios can be reported alongside throughput. The LRU rows are directly comparable;
 * Caffeine and the {@code ConcurrentHashMap} are not the same policy.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class ThreadScalingBenchmark {

    static final int KEY_SPACE = 100_000;

    private static final AtomicLong THREAD_SEEDS = new AtomicLong(1_000);

    @Param({"SYNC_LINKED_HASH_MAP", "VELOX_GLOBAL_LOCK", "VELOX_SHARDED_EXCLUSIVE",
            "VELOX_SHARDED_BUFFERED", "CAFFEINE", "GUAVA", "CONCURRENT_HASH_MAP"})
    public String impl;

    /**
     * The cache size as a percentage of the key space. 50 is read-heavy (~91% hit, few
     * writes); 10 is write-heavier (~72% hit); 1 is write-dominated (most requests miss and
     * every miss is a write), the third point the get-heavy/mixed/write-heavy spread asks for.
     */
    @Param({"1", "10", "50"})
    public int capacityPercent;

    /**
     * Shards used by the sharded variants. 64 is several times the core count, so two threads
     * rarely meet on one shard. Lowering it is how to ask "what does the read buffer buy
     * when the lock really is contended?".
     */
    @Param({"64"})
    public int shards;

    /** The cache operations the benchmark needs, so every implementation is driven identically. */
    interface BenchCache {
        Integer get(Integer key);

        void put(Integer key, Integer value);
    }

    private BenchCache cache;

    /** Kept so the trial can report what the run actually did (hit ratio, dropped reads). */
    private Cache<Integer, Integer> veloxCache;

    /** Pre-boxed keys, so the benchmark measures the cache and not Integer allocation. */
    private Integer[] boxed;
    private AliasSampler sampler;

    @Setup(Level.Trial)
    public void setup() {
        int capacity = KEY_SPACE * capacityPercent / 100;
        cache = create(impl, capacity, shards);
        if (cache instanceof VeloxBenchCache velox) {
            veloxCache = velox.cache;
        }
        boxed = new Integer[KEY_SPACE];
        for (int i = 0; i < KEY_SPACE; i++) {
            boxed[i] = i;
        }
        sampler = AliasSampler.zipf(KEY_SPACE, 0.99);

        // Fill the cache before measuring, so it starts from a realistic steady state.
        var random = new SplittableRandom(42);
        for (int i = 0; i < 2_000_000; i++) {
            Integer key = boxed[sampler.sample(random)];
            if (cache.get(key) == null) {
                cache.put(key, key);
            }
        }
    }

    @TearDown(Level.Trial)
    public void report() {
        if (veloxCache != null) {
            var stats = veloxCache.stats();
            long dropped = veloxCache instanceof ShardedCache<Integer, Integer> sharded ? sharded.droppedReads() : 0;
            System.out.printf("%n[trial-stats] impl=%s requests=%d hitRate=%.2f%% droppedReads=%d (%.3f%% of hits)%n",
                    impl, stats.requestCount(), stats.hitRate() * 100, dropped,
                    100.0 * dropped / Math.max(1, stats.hitCount()));
        }
    }

    /** Each benchmark thread has its OWN random stream and its own counters. */
    @State(Scope.Thread)
    public static class Cursor {
        SplittableRandom random;
        long hits;
        long requests;

        @Setup(Level.Trial)
        public void setup() {
            random = new SplittableRandom(THREAD_SEEDS.incrementAndGet());
        }

        @TearDown(Level.Trial)
        public void report() {
            System.out.printf("%n[thread-stats] hits=%d requests=%d%n", hits, requests);
        }
    }

    @Benchmark
    public void cacheAside(Cursor cursor, Blackhole blackhole) {
        Integer key = boxed[sampler.sample(cursor.random)];
        Integer value = cache.get(key);
        cursor.requests++;
        if (value == null) {
            value = key;
            cache.put(key, value);
        } else {
            cursor.hits++;
        }
        blackhole.consume(value);
    }

    // ------------------------------------------------------------------
    //  Implementations
    // ------------------------------------------------------------------

    static BenchCache create(String name, int capacity, int shards) {
        return switch (name) {
            case "SYNC_LINKED_HASH_MAP" -> synchronizedLinkedHashMap(capacity);
            case "VELOX_GLOBAL_LOCK" -> veloxBehindOneLock(capacity);
            case "VELOX_SHARDED_EXCLUSIVE" -> velox(capacity, shards, false);
            case "VELOX_SHARDED_BUFFERED" -> velox(capacity, shards, true);
            case "CAFFEINE" -> caffeine(capacity);
            case "GUAVA" -> guava(capacity);
            case "CONCURRENT_HASH_MAP" -> concurrentHashMap();
            default -> throw new IllegalArgumentException("unknown implementation: " + name);
        };
    }

    /** A Velox sharded cache, driven through the public API exactly as a user would. */
    private static final class VeloxBenchCache implements BenchCache {
        final Cache<Integer, Integer> cache;

        VeloxBenchCache(Cache<Integer, Integer> cache) {
            this.cache = cache;
        }

        @Override
        public Integer get(Integer key) {
            return cache.getIfPresent(key);
        }

        @Override
        public void put(Integer key, Integer value) {
            cache.put(key, value);
        }
    }

    private static BenchCache velox(int capacity, int shards, boolean bufferedReads) {
        return new VeloxBenchCache(CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(capacity)
                .concurrencyLevel(shards)
                .bufferedReads(bufferedReads)
                .build());
    }

    private static BenchCache caffeine(int capacity) {
        var cache = Caffeine.newBuilder().maximumSize(capacity).<Integer, Integer>build();
        return new BenchCache() {
            @Override
            public Integer get(Integer key) {
                return cache.getIfPresent(key);
            }

            @Override
            public void put(Integer key, Integer value) {
                cache.put(key, value);
            }
        };
    }

    /** Fully qualified to avoid colliding with com.velox.core.CacheBuilder, already imported above. */
    private static BenchCache guava(int capacity) {
        com.google.common.cache.Cache<Integer, Integer> cache =
                com.google.common.cache.CacheBuilder.newBuilder().maximumSize(capacity).build();
        return new BenchCache() {
            @Override
            public Integer get(Integer key) {
                return cache.getIfPresent(key);
            }

            @Override
            public void put(Integer key, Integer value) {
                cache.put(key, value);
            }
        };
    }

    private static BenchCache synchronizedLinkedHashMap(int capacity) {
        Map<Integer, Integer> map = Collections.synchronizedMap(
                new LinkedHashMap<>(capacity * 2, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                        return size() > capacity;
                    }
                });
        return new BenchCache() {
            @Override
            public Integer get(Integer key) {
                return map.get(key);
            }

            @Override
            public void put(Integer key, Integer value) {
                map.put(key, value);
            }
        };
    }

    private static BenchCache veloxBehindOneLock(int capacity) {
        var cache = new VeloxCache<Integer, Integer>(capacity, new LruPolicy<>());
        var lock = new ReentrantLock();
        return new BenchCache() {
            @Override
            public Integer get(Integer key) {
                lock.lock();
                try {
                    return cache.getIfPresent(key);
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void put(Integer key, Integer value) {
                lock.lock();
                try {
                    cache.put(key, value);
                } finally {
                    lock.unlock();
                }
            }
        };
    }

    private static BenchCache concurrentHashMap() {
        var map = new ConcurrentHashMap<Integer, Integer>(KEY_SPACE * 2);
        return new BenchCache() {
            @Override
            public Integer get(Integer key) {
                return map.get(key);
            }

            @Override
            public void put(Integer key, Integer value) {
                map.put(key, value);
            }
        };
    }
}
