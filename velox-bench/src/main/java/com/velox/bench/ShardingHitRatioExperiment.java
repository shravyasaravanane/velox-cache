package com.velox.bench;

import com.velox.core.Cache;
import com.velox.core.CacheBuilder;

import java.util.SplittableRandom;

/**
 * What does sharding cost in hit ratio?
 *
 * <h2>Why there is a cost</h2>
 *
 * A single LRU cache evicts the globally least recently used entry. A sharded cache gives
 * each of N shards 1/N of the capacity and lets each evict on its own. Keys are spread
 * across shards by hash, so a shard that happens to receive an unusually hot set of keys
 * evicts entries that a single global LRU, seeing the whole picture, would have kept.
 * That is the price of removing the global lock, and the Javadoc on {@code ShardedCache}
 * says it is small. This experiment measures whether that claim is true.
 *
 * <h2>Method</h2>
 *
 * Single-threaded and deterministic: the same Zipf(0.99) request stream over 100,000 keys
 * (drawn on the fly, so no sequence ever repeats), the same total capacity, replayed
 * through caches with 1, 4, 16, 64 and 256 shards. No concurrency is involved, so the only
 * thing that differs between rows is how the capacity is divided. (Read-buffer drops need
 * real contention and are reported by the throughput benchmark's per-trial statistics.)
 *
 * <p>Run with:
 * {@code java -cp velox-bench/target/benchmarks.jar com.velox.bench.ShardingHitRatioExperiment}
 */
public final class ShardingHitRatioExperiment {

    private static final int KEY_SPACE = 100_000;
    private static final int REQUESTS = 5_000_000;

    public static void main(String[] args) {
        int[] capacities = {1_000, 10_000, 50_000};
        int[] shardCounts = {1, 4, 16, 64, 256};

        AliasSampler sampler = AliasSampler.zipf(KEY_SPACE, 0.99);
        Integer[] boxed = new Integer[KEY_SPACE];
        for (int i = 0; i < KEY_SPACE; i++) {
            boxed[i] = i;
        }

        System.out.println();
        System.out.println("Hit ratio by shard count (Zipf 0.99 over " + KEY_SPACE + " keys, "
                + REQUESTS + " requests)");
        System.out.printf("%-10s", "capacity");
        for (int shards : shardCounts) {
            System.out.printf("%12s", shards + " shard" + (shards == 1 ? "" : "s"));
        }
        System.out.println();

        for (int capacity : capacities) {
            double[] ratios = new double[shardCounts.length];
            System.out.printf("%-10d", capacity);
            for (int i = 0; i < shardCounts.length; i++) {
                ratios[i] = run(capacity, shardCounts[i], sampler, boxed);
                System.out.printf("%11.2f%%", ratios[i] * 100);
            }
            System.out.println();
            System.out.printf("%-10s", "  vs 1:");
            for (double ratio : ratios) {
                System.out.printf("%+11.2f ", (ratio - ratios[0]) * 100);
            }
            System.out.println("  (percentage points)");
        }
        System.out.println();
    }

    private static double run(int capacity, int shards, AliasSampler sampler, Integer[] boxed) {
        Cache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .maximumSize(capacity)
                .concurrencyLevel(shards)
                .bufferedReads(false)                 // deterministic: reads reorder immediately
                .build();
        var random = new SplittableRandom(7);         // the SAME stream for every shard count
        long hits = 0;
        for (int i = 0; i < REQUESTS; i++) {
            Integer key = boxed[sampler.sample(random)];
            if (cache.getIfPresent(key) != null) {
                hits++;
            } else {
                cache.put(key, key);
            }
        }
        return (double) hits / REQUESTS;
    }

    private ShardingHitRatioExperiment() {
    }
}
