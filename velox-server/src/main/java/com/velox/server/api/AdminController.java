package com.velox.server.api;

import com.velox.core.policy.Policy;
import com.velox.server.cache.HotSwappableCache;
import com.velox.server.cache.ProductCacheService;
import com.velox.server.domain.ProductDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Live administration of the primary cache -- the endpoints that make a demo compelling: flip
 * the policy or the capacity mid-traffic and watch the hit-ratio line on {@code GET /api/stats}
 * bend in response, with no restart.
 */
@RestController
public class AdminController {

    private final HotSwappableCache<Long, ProductDetails> primaryCache;
    private final ProductCacheService productCacheService;

    public AdminController(HotSwappableCache<Long, ProductDetails> primaryCache,
            ProductCacheService productCacheService) {
        this.primaryCache = primaryCache;
        this.productCacheService = productCacheService;
    }

    /**
     * Rebuilds the primary cache empty, with a new eviction policy at its current capacity.
     * See {@link HotSwappableCache} for what happens to entries already held.
     */
    @PostMapping("/api/admin/policy/{name}")
    public ResponseEntity<?> swapPolicy(@PathVariable String name) {
        Policy policy;
        try {
            policy = Policy.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("unknown policy '" + name + "'");
        }
        primaryCache.swapPolicy(policy);
        return ResponseEntity.noContent().build();
    }

    /** Rebuilds the primary cache empty, at a new capacity with its current policy. */
    @PostMapping("/api/admin/capacity/{n}")
    public ResponseEntity<?> resize(@PathVariable int n) {
        if (n < 1) {
            return ResponseEntity.badRequest().body("capacity must be at least 1, got " + n);
        }
        primaryCache.resize(n);
        return ResponseEntity.noContent().build();
    }

    /** Drops every entry from the primary cache, without touching its policy or capacity. */
    @PostMapping("/api/admin/invalidate")
    public ResponseEntity<Void> invalidate() {
        primaryCache.invalidateAll();
        return ResponseEntity.noContent().build();
    }

    /** Drops every cached product filed under {@code tag} (e.g. {@code category:42}). */
    @PostMapping("/api/admin/invalidate-tag/{tag}")
    public ResponseEntity<Integer> invalidateTag(@PathVariable String tag) {
        return ResponseEntity.ok(productCacheService.invalidateByTag(tag));
    }
}
