package com.velox.server.cache;

import com.velox.core.Cache;
import com.velox.core.CacheBuilder;
import com.velox.core.policy.Policy;
import com.velox.server.domain.ProductDetails;
import com.velox.server.repository.CatalogQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The A/B side of {@code GET /api/products/{id}?cache=off|lru|arc|w_tiny_lfu}: a small set of
 * independent, named caches -- separate instances from {@link CacheConfig}'s write-integrated
 * {@code productCache} -- that exist purely so a live traffic sample can be pointed at different
 * policies (or none at all) and compared side by side.
 *
 * <h2>Why these are separate caches, not views onto the primary one</h2>
 *
 * The primary cache is what the write endpoints keep correct: write-through refreshes it,
 * write-around invalidates it, tag-based invalidation drops entries from it. Routing comparison
 * traffic through it would mean every policy under test shares one eviction history, which
 * defeats the comparison. These variants are read-only, disposable, and never touched by a
 * write -- exactly like the shadow caches {@code docs/SYSTEM.md} describes for the Tier 6 Policy
 * Arena, just without that tier's live animation.
 *
 * <h2>{@code off}</h2>
 *
 * Not a cache at all: every request goes straight to {@link CatalogQueryService}. This is the
 * baseline the headline throughput numbers are measured against.
 *
 * <h2>Why every variant is built with {@code concurrencyLevel}</h2>
 *
 * Same reason as {@link HotSwappableCache}: a bare {@code CacheBuilder.build()} with no
 * {@code concurrencyLevel} is single-threaded, and these caches are hit from many servlet
 * threads at once.
 */
@Component
public class CacheVariants {

    /** The sentinel variant name that bypasses caching entirely. */
    public static final String OFF = "off";

    private final CatalogQueryService catalogQueryService;
    private final Map<String, Cache<Long, ProductDetails>> caches;
    private final Map<String, VariantStats> stats;

    public CacheVariants(CatalogQueryService catalogQueryService,
            @Value("${velox.demo.cache-capacity:10000}") int capacity,
            @Value("${velox.demo.ab-policies:LRU,ARC,W_TINY_LFU}") String policyNames,
            @Value("${velox.demo.cache-shards:16}") int shards) {
        this.catalogQueryService = catalogQueryService;

        Map<String, Cache<Long, ProductDetails>> builtCaches = new LinkedHashMap<>();
        Map<String, VariantStats> builtStats = new LinkedHashMap<>();
        builtStats.put(OFF, new VariantStats());

        for (String raw : policyNames.split(",")) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Policy policy = Policy.valueOf(trimmed.toUpperCase(Locale.ROOT));
            String key = variantKey(policy);
            builtCaches.put(key, CacheBuilder.<Long, ProductDetails>newBuilder()
                    .maximumSize(capacity)
                    .policy(policy)
                    .concurrencyLevel(shards)
                    .build());
            builtStats.put(key, new VariantStats());
        }

        this.caches = Map.copyOf(builtCaches);
        this.stats = Map.copyOf(builtStats);
    }

    private static String variantKey(Policy policy) {
        return policy.name().toLowerCase(Locale.ROOT);
    }

    /** @return every variant name this comparison accepts, including {@link #OFF} */
    public java.util.Set<String> variantNames() {
        return stats.keySet();
    }

    /**
     * Serves {@code id} through the named variant, timing the call.
     *
     * @throws IllegalArgumentException if {@code variant} is not {@link #OFF} and not one of
     *                                   {@link #variantNames()}
     */
    public Optional<ProductDetails> get(String variant, long id) {
        String key = variant.toLowerCase(Locale.ROOT);
        VariantStats variantStats = stats.get(key);
        if (variantStats == null) {
            throw new IllegalArgumentException(
                    "unknown cache variant '" + variant + "': expected one of " + variantNames());
        }

        long start = System.nanoTime();
        try {
            if (OFF.equals(key)) {
                return catalogQueryService.findProductDetails(id);
            }
            Cache<Long, ProductDetails> cache = caches.get(key);
            return Optional.ofNullable(
                    cache.get(id, missedId -> catalogQueryService.findProductDetails(missedId).orElse(null)));
        } finally {
            variantStats.record(System.nanoTime() - start);
        }
    }

    /** @return a comparison report across every variant, keyed the same way {@link #get} accepts */
    public Map<String, VariantReport> report() {
        Map<String, VariantReport> result = new LinkedHashMap<>();
        stats.forEach((name, variantStats) -> {
            Cache<Long, ProductDetails> cache = caches.get(name);
            double hitRatePercent = cache == null ? 0.0 : cache.stats().hitRate() * 100.0;
            result.put(name, new VariantReport(variantStats.requestCount(),
                    variantStats.averageLatencyMicros(), hitRatePercent));
        });
        return result;
    }

    /** @param requestCount how many requests this variant has served since startup
     *  @param averageLatencyMicros mean end-to-end latency of those requests, cache or database
     *  @param hitRatePercent {@code 0.0} for {@link #OFF}, which never caches anything */
    public record VariantReport(long requestCount, double averageLatencyMicros, double hitRatePercent) {
    }

    /** A minimal, lock-free running average, kept per variant. */
    private static final class VariantStats {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();

        void record(long elapsedNanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(elapsedNanos);
        }

        long requestCount() {
            return count.get();
        }

        double averageLatencyMicros() {
            long requests = count.get();
            return requests == 0 ? 0.0 : (totalNanos.get() / 1000.0) / requests;
        }
    }
}
