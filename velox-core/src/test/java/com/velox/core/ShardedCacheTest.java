package com.velox.core;

import com.velox.core.concurrent.ShardRouter;
import com.velox.core.expiry.FakeTicker;
import com.velox.core.policy.Policy;
import com.velox.core.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single-threaded correctness of {@link ShardedCache}: does splitting the cache into
 * shards, and deferring reads through a buffer, preserve the behaviour of a cache?
 * (Concurrent behaviour is in {@code ShardedCacheStressTest}.)
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ShardedCacheTest {

    private static ShardedCache<String, Integer> sharded(int capacity, int shards, boolean buffered) {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(capacity)
                .concurrencyLevel(shards)
                .bufferedReads(buffered)
                .readBufferSize(4096)
                .build();
        return (ShardedCache<String, Integer>) cache;
    }

    // ------------------------------------------------------------------
    //  Basics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the builder returns a sharded cache only when concurrencyLevel is set")
    void builderChoosesTheImplementation() {
        Cache<String, Integer> plain = CacheBuilder.<String, Integer>newBuilder().build();
        Cache<String, Integer> concurrent = CacheBuilder.<String, Integer>newBuilder().concurrencyLevel(4).build();

        assertInstanceOf(VeloxCache.class, plain, "the default is the single-threaded engine");
        assertInstanceOf(ShardedCache.class, concurrent);
    }

    @Test
    @DisplayName("stores, finds, overwrites and removes across shards")
    void basicOperations() {
        var cache = sharded(1000, 8, true);

        for (int i = 0; i < 100; i++) {
            cache.put("k" + i, i);
        }
        for (int i = 0; i < 100; i++) {
            assertEquals(i, cache.getIfPresent("k" + i));
        }
        cache.put("k5", 500);
        cache.invalidate("k6");

        assertEquals(500, cache.getIfPresent("k5"));
        assertNull(cache.getIfPresent("k6"));
        assertEquals(99, cache.size());
        assertTrue(cache.containsKey("k7"));
        assertFalse(cache.containsKey("k6"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("the shard count is a power of two and shrinks when the capacity is too small to share")
    void shardCountIsSensible() {
        assertEquals(8, sharded(1000, 8, true).shardCount());
        assertEquals(16, sharded(1000, 9, true).shardCount(), "rounded up to a power of two");
        assertEquals(2, sharded(3, 16, true).shardCount(), "3 entries cannot give 16 shards a share each");
        assertEquals(1, sharded(1, 16, true).shardCount());
    }

    @Test
    @DisplayName("the whole capacity is usable and never exceeded")
    void capacityIsSharedExactly() {
        var cache = sharded(100, 4, true);

        for (int i = 0; i < 10_000; i++) {
            cache.put("key" + i, i);
            assertTrue(cache.size() <= 100);
        }

        assertEquals(100, cache.size(), "with 10,000 keys every shard is full, so the shares must sum to 100");
        assertEquals(100, cache.maximumSize());
        assertEquals(100, cache.maximumWeight());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("null keys and values are rejected")
    void rejectsNulls() {
        var cache = sharded(10, 2, true);

        assertThrows(NullPointerException.class, () -> cache.put(null, 1));
        assertThrows(NullPointerException.class, () -> cache.put("A", null));
        assertThrows(NullPointerException.class, () -> cache.getIfPresent(null));
        assertThrows(NullPointerException.class, () -> cache.invalidate(null));
    }

    @Test
    @DisplayName("statistics are combined across shards")
    void statsAggregate() {
        var cache = sharded(1000, 8, true);
        for (int i = 0; i < 50; i++) {
            cache.put("k" + i, i);
        }

        for (int i = 0; i < 50; i++) {
            cache.getIfPresent("k" + i);                 // 50 hits, on the buffered fast path
        }
        for (int i = 0; i < 20; i++) {
            cache.getIfPresent("absent" + i);            // 20 misses, on the exclusive path
        }

        var stats = cache.stats();
        assertEquals(50, stats.hitCount());
        assertEquals(20, stats.missCount());
        assertEquals(70, stats.requestCount());
    }

    @Test
    @DisplayName("get(key, loader) loads on a miss and caches the result")
    void loaderPath() {
        var cache = sharded(100, 4, true);
        var loads = new ArrayList<String>();

        int first = cache.get("A", k -> {
            loads.add(k);
            return 7;
        });
        int second = cache.get("A", k -> {
            loads.add(k);
            return 99;
        });

        assertEquals(7, first);
        assertEquals(7, second);
        assertEquals(List.of("A"), loads);
        assertEquals(1, cache.stats().loadCount());
    }

    @Test
    @DisplayName("a busy read path keeps draining its buffer, so reads are not dropped")
    void readsKeepTheBufferDrained() {
        // A 4-slot buffer fills after four reads. If reads did not trigger a drain, every
        // hit after that would be silently discarded and recency would quietly rot, with no
        // error anywhere. Single-threaded, a drain always succeeds, so nothing may drop.
        var cache = (ShardedCache<String, Integer>) CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(10).concurrencyLevel(1).readBufferSize(4).build();
        cache.put("A", 1);

        for (int i = 0; i < 1_000; i++) {
            assertEquals(1, cache.getIfPresent("A"));
        }

        assertEquals(0, cache.droppedReads());
        assertEquals(1_000, cache.stats().hitCount());
    }

    @Test
    @DisplayName("the fast read path runs under the shared lock")
    void fastPathHoldsTheSharedLock() {
        // An unlocked reader is a data race under the Java memory model, but on this hardware
        // it cannot be caught by behaviour: it can only see a false miss (repaired by the
        // exclusive fallback) or a value that was valid a moment ago. So observe the lock
        // directly: a hit on an entry with a deadline reads the clock while doing the lookup,
        // and from inside that clock read we ask whether the lock is held.
        var holder = new AtomicReference<ShardedCache<String, Integer>>();
        var lockStates = new ArrayList<Boolean>();
        var cache = (ShardedCache<String, Integer>) CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(10)
                .concurrencyLevel(1)
                .expireAfterWrite(Duration.ofHours(1))
                .ticker(() -> {
                    ShardedCache<String, Integer> c = holder.get();
                    if (c != null) {
                        lockStates.add(c.holdsLockFor("A"));
                    }
                    return 0L;
                })
                .build();
        holder.set(cache);
        cache.put("A", 1);
        lockStates.clear();

        assertEquals(1, cache.getIfPresent("A"));        // a hit, served on the fast path

        assertEquals(List.of(true), lockStates,
                "the lookup must read the clock exactly once, and while holding the shard lock");
    }

    @Test
    @DisplayName("reads deferred through the buffer still protect an entry from eviction")
    void bufferedReadsStillCountAsUse() {
        var cache = sharded(3, 1, true);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.getIfPresent("A");                       // buffered: not yet applied to the recency list
        cache.put("D", 4);                             // the write must apply it BEFORE choosing a victim

        assertEquals(1, cache.getIfPresent("A"), "A was read, so it must have survived");
        assertNull(cache.getIfPresent("B"), "B was the least recently used and is the victim");
    }

    // ------------------------------------------------------------------
    //  Capacity and weight
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an entry can weigh at most one shard's share, even if the total would hold it")
    void weightLimitIsPerShard() {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumWeight(100)
                .weigher((k, v) -> v)
                .concurrencyLevel(4)
                .build();

        cache.put("fits", 25);                             // exactly one shard's share
        cache.put("too-heavy-for-a-shard", 30);            // less than the total, more than a share

        assertEquals(25, cache.getIfPresent("fits"));
        assertNull(cache.getIfPresent("too-heavy-for-a-shard"),
                "the documented cost of sharding a weight budget");
        assertEquals(1, cache.stats().rejectionCount());
    }

    // ------------------------------------------------------------------
    //  Expiry
    // ------------------------------------------------------------------

    @Test
    @DisplayName("entries expire in every shard, and cleanUp sweeps them all")
    void expiryAcrossShards() {
        var clock = new FakeTicker();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(1000)
                .concurrencyLevel(8)
                .expireAfterWrite(Duration.ofSeconds(10))
                .ticker(clock)
                .build();
        for (int i = 0; i < 200; i++) {
            cache.put("k" + i, i);
        }
        clock.advance(Duration.ofSeconds(11));

        assertNull(cache.getIfPresent("k3"), "an expired entry is a miss");
        cache.cleanUp();

        assertEquals(0, cache.size());
        assertEquals(200, cache.stats().expirationCount());
    }

    @Test
    @DisplayName("expireAfterAccess turns buffered reads off, and still works")
    void accessExpiryDisablesBufferedReads() {
        var clock = new FakeTicker();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(100)
                .concurrencyLevel(4)
                .expireAfterAccess(Duration.ofSeconds(5))
                .ticker(clock)
                .build();
        cache.put("A", 1);

        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(4));
            assertEquals(1, cache.getIfPresent("A"), "each read restarts the idle clock");
        }

        assertFalse(((ShardedCache<String, Integer>) cache).usesBufferedReads());
    }

    // ------------------------------------------------------------------
    //  Removal listeners
    // ------------------------------------------------------------------

    @Test
    @DisplayName("removal listeners run AFTER the shard lock is released")
    void listenersRunOutsideTheLock() {
        // If the listener ran while the shard's write lock was held, another thread
        // trying to use the same shard would block until the listener returned. So the
        // listener hands a probe to a second thread and waits for it: if the probe cannot
        // finish, the lock is being held across user code.
        var holder = new AtomicReference<ShardedCache<String, Integer>>();
        var probeFinishedInTime = new AtomicBoolean();
        var listenerRan = new AtomicBoolean();

        Cache<String, Integer> built = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(1)
                .concurrencyLevel(1)
                .removalListener((k, v, c) -> {
                    listenerRan.set(true);
                    Thread probe = new Thread(() -> holder.get().containsKey("anything"));
                    probe.start();
                    try {
                        probe.join(3_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    probeFinishedInTime.set(!probe.isAlive());
                })
                .build();
        var cache = (ShardedCache<String, Integer>) built;
        holder.set(cache);
        cache.put("A", 1);

        cache.put("B", 2);                                           // evicts A: the listener fires

        assertTrue(listenerRan.get());
        assertTrue(probeFinishedInTime.get(),
                "the probe was blocked, so the listener ran while the shard lock was held");
    }

    @Test
    @DisplayName("a listener may call back into the cache without deadlocking")
    void listenerMayReenter() {
        var holder = new AtomicReference<ShardedCache<String, Integer>>();
        var reinserted = new AtomicBoolean();
        Cache<String, Integer> built = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(2)
                .concurrencyLevel(1)
                .removalListener((k, v, c) -> {
                    if (k.equals("A") && reinserted.compareAndSet(false, true)) {
                        holder.get().put("D", 4);
                    }
                })
                .build();
        var cache = (ShardedCache<String, Integer>) built;
        holder.set(cache);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.put("C", 3);                                           // evicts A; A's listener puts D, evicting B

        assertTrue(cache.containsKey("C"));
        assertTrue(cache.containsKey("D"));
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Differential test against a naive sharded LRU
    // ------------------------------------------------------------------

    /**
     * The reference: one access-ordered LinkedHashMap per shard, routed with the same
     * router and hash. Each shard evicts on its own, exactly as the real thing must.
     */
    private static final class NaiveShardedLru {
        private final ShardRouter router;
        private final List<LinkedHashMap<String, Integer>> shards = new ArrayList<>();
        private final int[] capacities;

        NaiveShardedLru(int total, int shardCount) {
            router = new ShardRouter(shardCount);
            int n = router.shardCount();
            capacities = new int[n];
            for (int i = 0; i < n; i++) {
                capacities[i] = total / n + (i < total % n ? 1 : 0);
                shards.add(new LinkedHashMap<>(16, 0.75f, true));
            }
        }

        private int shard(String key) {
            return router.shardFor(Hashing.spread(key));
        }

        Integer get(String key) {
            return shards.get(shard(key)).get(key);
        }

        void put(String key, int value) {
            int index = shard(key);
            var map = shards.get(index);
            if (!map.containsKey(key) && map.size() >= capacities[index]) {
                Iterator<String> eldest = map.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
            map.put(key, value);
        }

        void invalidate(String key) {
            shards.get(shard(key)).remove(key);
        }

        int size() {
            return shards.stream().mapToInt(Map::size).sum();
        }

        Set<String> keys() {
            var all = new TreeSet<String>();
            shards.forEach(m -> all.addAll(m.keySet()));
            return all;
        }
    }

    @ParameterizedTest(name = "{0} shard(s), buffered reads = {1}")
    @CsvSource({"1,true", "1,false", "4,true", "4,false", "16,true", "16,false"})
    @DisplayName("matches a naive sharded LRU exactly over 120,000 random operations")
    void differentialAgainstNaiveShardedLru(int shards, boolean buffered) {
        var real = sharded(120, shards, buffered);
        var naive = new NaiveShardedLru(120, shards);
        var random = new Random(4242L + shards);
        int keySpace = 300;

        for (int step = 0; step < 120_000; step++) {
            String key = "key" + (int) (keySpace * Math.pow(random.nextDouble(), 2.0));
            int action = random.nextInt(10);

            if (action < 6) {
                assertEquals(naive.get(key), real.getIfPresent(key), "step " + step + " get(" + key + ")");
            } else if (action < 9) {
                int value = random.nextInt(1_000_000);
                naive.put(key, value);
                real.put(key, value);
            } else {
                naive.invalidate(key);
                real.invalidate(key);
            }

            assertEquals(naive.size(), real.size(), "size diverged at step " + step);
            if (step % 250 == 0) {
                var present = new TreeSet<String>();
                for (int i = 0; i < keySpace; i++) {
                    if (real.containsKey("key" + i)) {
                        present.add("key" + i);
                    }
                }
                assertEquals(naive.keys(), present, "surviving keys diverged at step " + step);
                real.assertInvariants();
            }
        }
        real.assertInvariants();
        // The equivalence only means something if no read record was thrown away.
        assertEquals(0, real.droppedReads(), "a dropped read would legitimately change eviction order");
    }

    @Test
    @DisplayName("every policy works when sharded")
    void everyPolicyWorksSharded() {
        for (Policy policy : Policy.values()) {
            Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                    .maximumSize(64).concurrencyLevel(4).policy(policy).build();
            var random = new Random(policy.ordinal());

            for (int i = 0; i < 5_000; i++) {
                String key = "k" + random.nextInt(200);
                if (cache.getIfPresent(key) == null) {
                    cache.put(key, i);
                }
                assertTrue(cache.size() <= 64, policy + " exceeded capacity");
            }
            ((ShardedCache<String, Integer>) cache).assertInvariants();
        }
    }
}
