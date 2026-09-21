package com.velox.bench;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * How does throughput change as threads are added?
 *
 * <h2>What this measures</h2>
 *
 * A web server's cache is hit by many threads at once, so "constant time" has to
 * hold when sixteen threads are calling {@code get} together, not just when one is.
 * This benchmark runs the cache-aside pattern (look up; on a miss, store) with a
 * Zipf-distributed key stream, and reports <b>total operations per millisecond
 * across all threads</b>. Run it at 1, 2, 4, 8 and 16 threads with JMH's
 * {@code -t} option.
 *
 * <p>If a cache scaled perfectly, doubling the threads would double the throughput.
 *
 * <h2>The implementations</h2>
 *
 * <ul>
 *   <li>{@code SYNC_LINKED_HASH_MAP} -- the textbook Java LRU: an access-ordered
 *       {@code LinkedHashMap} behind {@code Collections.synchronizedMap}.</li>
 *   <li>{@code VELOX_GLOBAL_LOCK} -- our own single-threaded engine wrapped in one
 *       lock. The naive way to make our cache thread-safe.</li>
 *   <li>{@code CONCURRENT_HASH_MAP} -- <b>no eviction, no ordering</b>. Not a cache at
 *       all: it grows to hold every key. It is the ceiling, showing what a hash map
 *       can do when it is not also maintaining LRU order.</li>
 * </ul>
 *
 * <h2>Why a read is expensive here</h2>
 *
 * In an LRU cache a {@code get} is not a read: it moves the entry to the front of
 * the recency list, which is a write to shared structure. So every lookup needs the
 * exclusive lock, and all threads queue up single-file through it.
 *
 * <h2>Reading the results honestly</h2>
 *
 * The two LRU implementations are directly comparable (same policy, same capacity,
 * same workload). The {@code ConcurrentHashMap} row has a ~100% hit ratio because
 * it never evicts, so it is a ceiling, not a competitor.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class ThreadScalingBenchmark {

    static final int KEY_SPACE = 100_000;
    static final int CAPACITY = 10_000;                 // 10% of the key space, so eviction is constant
    static final int SAMPLE_SIZE = 1 << 20;
    static final int SAMPLE_MASK = SAMPLE_SIZE - 1;

    @Param({"SYNC_LINKED_HASH_MAP", "VELOX_GLOBAL_LOCK", "CONCURRENT_HASH_MAP"})
    public String impl;

    /** The cache operations the benchmark needs, so every implementation is driven identically. */
    interface BenchCache {
        Integer get(Integer key);

        void put(Integer key, Integer value);
    }

    private BenchCache cache;

    /** Pre-boxed keys, so the benchmark measures the cache and not Integer allocation. */
    private Integer[] keys;

    @Setup(Level.Trial)
    public void setup() {
        cache = create(impl);
        keys = zipfSample(KEY_SPACE, 0.99, SAMPLE_SIZE, 42);

        // Fill the cache once so measurement starts from a realistic steady state
        // rather than an empty cache.
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            Integer key = keys[i];
            if (cache.get(key) == null) {
                cache.put(key, key);
            }
        }
    }

    /** Each benchmark thread walks the shared key stream from its own starting point. */
    @State(Scope.Thread)
    public static class Cursor {
        int position;

        @Setup(Level.Trial)
        public void setup() {
            position = new Random(Thread.currentThread().threadId()).nextInt(SAMPLE_SIZE);
        }
    }

    @Benchmark
    public void cacheAside(Cursor cursor, Blackhole blackhole) {
        Integer key = keys[cursor.position++ & SAMPLE_MASK];
        Integer value = cache.get(key);
        if (value == null) {
            value = key;
            cache.put(key, value);
        }
        blackhole.consume(value);
    }

    // ------------------------------------------------------------------
    //  Implementations
    // ------------------------------------------------------------------

    static BenchCache create(String name) {
        return switch (name) {
            case "SYNC_LINKED_HASH_MAP" -> synchronizedLinkedHashMap();
            case "VELOX_GLOBAL_LOCK" -> veloxBehindOneLock();
            case "CONCURRENT_HASH_MAP" -> concurrentHashMap();
            default -> throw new IllegalArgumentException("unknown implementation: " + name);
        };
    }

    private static BenchCache synchronizedLinkedHashMap() {
        Map<Integer, Integer> map = Collections.synchronizedMap(
                new LinkedHashMap<>(CAPACITY * 2, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                        return size() > CAPACITY;
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

    private static BenchCache veloxBehindOneLock() {
        var cache = new VeloxCache<Integer, Integer>(CAPACITY, new LruPolicy<>());
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

    // ------------------------------------------------------------------
    //  Workload
    // ------------------------------------------------------------------

    /**
     * Zipf-distributed keys: rank i is requested with probability proportional to
     * 1 / (i+1)^theta. Real web traffic looks like this, with a few keys receiving
     * most requests. Sampled by binary search over the cumulative weights.
     * (Tier 4 replaces this with Vose's alias method, which samples in O(1).)
     */
    static Integer[] zipfSample(int keySpace, double theta, int count, long seed) {
        Integer[] boxed = new Integer[keySpace];
        for (int i = 0; i < keySpace; i++) {
            boxed[i] = i;
        }
        double[] cumulative = new double[keySpace];
        double total = 0;
        for (int i = 0; i < keySpace; i++) {
            total += 1.0 / Math.pow(i + 1, theta);
            cumulative[i] = total;
        }
        var random = new Random(seed);
        Integer[] sample = new Integer[count];
        for (int i = 0; i < count; i++) {
            int index = Arrays.binarySearch(cumulative, random.nextDouble() * total);
            sample[i] = boxed[index >= 0 ? index : -index - 1];
        }
        return sample;
    }
}
