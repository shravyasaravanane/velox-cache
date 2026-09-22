package com.velox.server.api;

import com.velox.core.stats.CacheStats;
import com.velox.server.cache.CacheVariants;
import com.velox.server.cache.HotSwappableCache;
import com.velox.server.cache.WriteBehindBuffer;
import com.velox.server.domain.ProductDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /api/stats}: everything this tier measures, in one response -- the primary cache's
 * full {@link CacheStats} snapshot, the A/B comparison across {@link CacheVariants}, and
 * {@link WriteBehindBuffer}'s write-amplification numbers. This is the endpoint the load
 * generator's instructions point back to: run it, hit {@code /api/stats}, change {@code cache=},
 * run it again, compare.
 */
@RestController
public class StatsController {

    private final HotSwappableCache<Long, ProductDetails> primaryCache;
    private final CacheVariants cacheVariants;
    private final WriteBehindBuffer writeBehindBuffer;

    public StatsController(HotSwappableCache<Long, ProductDetails> primaryCache, CacheVariants cacheVariants,
            WriteBehindBuffer writeBehindBuffer) {
        this.primaryCache = primaryCache;
        this.cacheVariants = cacheVariants;
        this.writeBehindBuffer = writeBehindBuffer;
    }

    @GetMapping("/api/stats")
    public Response stats() {
        return new Response(
                new Primary(primaryCache.currentPolicy().name(), primaryCache.currentCapacity(),
                        primaryCache.size(), primaryCache.stats()),
                cacheVariants.report(),
                writeBehindBuffer.stats());
    }

    public record Primary(String policy, int capacity, int size, CacheStats stats) {
    }

    public record Response(Primary primary, Map<String, CacheVariants.VariantReport> variants,
            WriteBehindBuffer.Stats writeBehind) {
    }
}
