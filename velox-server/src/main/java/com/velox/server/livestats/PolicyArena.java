package com.velox.server.livestats;

import com.velox.core.Cache;
import com.velox.core.CacheBuilder;
import com.velox.core.policy.Policy;
import com.velox.core.stats.CacheStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M6.4, the Policy Arena: replays the real live traffic stream into a <b>shadow cache</b> per
 * policy -- every policy in the roster races against the identical sequence of requests, so the
 * leaderboard is a genuine head-to-head, the live equivalent of {@code velox-bench}'s
 * hit-ratio matrix (Tier 4, M4.3).
 *
 * <h2>Why this needed no new introspection API on {@code EvictionPolicy}</h2>
 *
 * A shadow cache stores <b>keys only, never values</b> -- there is no database row, no
 * {@link com.velox.server.domain.ProductDetails}, just "is this id currently resident." That is
 * exactly what {@code velox-bench}'s {@code PolicySimulation} already does to build the
 * hit-ratio matrix: {@code new VeloxCache<Integer, Integer>(capacity, policy.create(capacity))},
 * storing each key as its own value. Reusing that same shape here -- {@code Cache<Long, Long>}
 * per policy, built through the same public {@link CacheBuilder} every other cache in this
 * application uses -- means the Arena needed no new backend design at the policy level, only a
 * place to run several of them at once and compare.
 *
 * <h2>Why {@code synchronized}, not sharded</h2>
 *
 * Every other cache in this application ({@link com.velox.server.cache.HotSwappableCache},
 * {@link com.velox.server.cache.CacheVariants}) is built with a {@code concurrencyLevel} because
 * it gates real request throughput. These shadow caches gate nothing -- they exist purely to be
 * compared, and a <i>fair</i> comparison needs every policy to see the exact same total order of
 * accesses, not merely similar traffic arriving through independently-locked shards. A single
 * {@code synchronized} method achieves that directly, and is cheap enough to afford: each shadow
 * cache's own operation is O(1) key-only bookkeeping, no database call, no value payload --
 * racing a dozen policies costs a dozen hash lookups per request, not a bottleneck.
 */
@Component
public class PolicyArena {

    private record Shadow(Policy policy, Cache<Long, Long> cache) {
    }

    private final List<Shadow> shadows;

    public PolicyArena(@Value("${velox.demo.arena-capacity:1000}") int capacity,
            @Value("${velox.demo.arena-policies:LRU,FIFO,RANDOM,CLOCK,LFU,LFU_AGED,SLRU,TWO_Q,LRU_K,ARC,W_TINY_LFU}")
            String policyNames) {
        List<Shadow> built = new ArrayList<>();
        for (String raw : policyNames.split(",")) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Policy policy = Policy.valueOf(trimmed.toUpperCase(Locale.ROOT));
            Cache<Long, Long> cache = CacheBuilder.<Long, Long>newBuilder()
                    .maximumSize(capacity)
                    .policy(policy)
                    .build(); // deliberately single-threaded VeloxCache -- see record()'s class Javadoc
            built.add(new Shadow(policy, cache));
        }
        this.shadows = List.copyOf(built);
    }

    /** Replays one real request's key into every shadow cache, in lock-step. */
    public synchronized void record(long key) {
        for (Shadow shadow : shadows) {
            if (shadow.cache.getIfPresent(key) == null) {
                shadow.cache.put(key, key);
            }
        }
    }

    /** @return the current leaderboard, hit rate descending */
    public synchronized List<Standing> standings() {
        List<Standing> result = new ArrayList<>(shadows.size());
        for (Shadow shadow : shadows) {
            CacheStats stats = shadow.cache.stats();
            result.add(new Standing(shadow.policy.name(), stats.hitCount(), stats.missCount(), stats.hitRate() * 100.0));
        }
        result.sort((a, b) -> Double.compare(b.hitRatePercent(), a.hitRatePercent()));
        return result;
    }

    public record Standing(String policy, long hits, long misses, double hitRatePercent) {
    }
}
