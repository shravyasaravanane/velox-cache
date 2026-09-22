package com.velox.server.api;

import com.velox.server.cache.CacheVariants;
import com.velox.server.cache.ProductCacheService;
import com.velox.server.cache.ProductUpdate;
import com.velox.server.domain.ProductDetails;
import com.velox.server.livestats.LatencyRingBuffer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read and write paths this tier exists to make fast and correct. Every write pattern
 * {@link ProductCacheService} implements is reachable here via {@code mode}, so each one
 * stays independently demonstrable rather than picked once and hidden behind a single
 * "update" endpoint.
 */
@RestController
public class ProductController {

    private final ProductCacheService productCacheService;
    private final CacheVariants cacheVariants;
    private final LatencyRingBuffer latencyRingBuffer;

    public ProductController(ProductCacheService productCacheService, CacheVariants cacheVariants,
            LatencyRingBuffer latencyRingBuffer) {
        this.productCacheService = productCacheService;
        this.cacheVariants = cacheVariants;
        this.latencyRingBuffer = latencyRingBuffer;
    }

    /**
     * Cache-aside read. With no {@code cache} parameter this is the default path -- the
     * write-integrated primary cache, and the one {@code docs/SYSTEM.md}'s headline numbers
     * measure. With {@code cache}, it instead serves from one of {@link CacheVariants}'
     * independent, read-only comparison caches ({@code off}, {@code lru}, {@code arc},
     * {@code w_tiny_lfu} by default) -- the A/B toggle: point a live load generator at the same
     * id range under each value and compare {@code GET /api/stats}' per-variant hit rate and
     * latency afterward.
     *
     * <p>Only the default (primary-cache) path feeds {@link LatencyRingBuffer}, which drives the
     * live dashboard's latency percentile bars -- the dashboard watches the one cache the admin
     * hot-swap endpoints actually control, not the disposable A/B comparison caches.
     */
    @GetMapping("/api/products/{id}")
    public ResponseEntity<?> getProduct(@PathVariable long id,
            @RequestParam(required = false) String cache) {
        if (cache == null) {
            long start = System.nanoTime();
            try {
                return productCacheService.getCacheAside(id)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build());
            } finally {
                latencyRingBuffer.record(System.nanoTime() - start);
            }
        }
        try {
            return cacheVariants.get(cache, id)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * @param mode {@code write-through} (default) updates the cache with fresh data
     *             immediately; {@code write-around} leaves the cache to reload on the next
     *             read; {@code write-behind} updates the cache now and defers the database
     *             write; {@code delayed-double-delete} is the invalidation pattern described
     *             in {@link ProductCacheService#invalidateWithDelayedDoubleDelete}
     */
    @PostMapping("/api/products/{id}")
    public ResponseEntity<?> updateProduct(@PathVariable long id, @RequestBody ProductUpdate update,
            @RequestParam(defaultValue = "write-through") String mode) {
        return switch (mode) {
            case "write-through" -> productCacheService.writeThrough(id, update)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
            case "write-around" -> productCacheService.writeAround(id, update)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
            case "write-behind" -> ResponseEntity.ok(productCacheService.writeBehind(id, update));
            case "delayed-double-delete" -> productCacheService.invalidateWithDelayedDoubleDelete(id, update)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
            default -> ResponseEntity.badRequest()
                    .body("unknown mode '" + mode + "': expected write-through, write-around, "
                            + "write-behind or delayed-double-delete");
        };
    }
}
