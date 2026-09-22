package com.velox.server.api;

import com.velox.server.domain.ProductDetails;
import com.velox.server.repository.CatalogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read path this entire tier exists to make fast. {@code GET /api/products/{id}} is
 * plain cache-aside today (see {@link com.velox.server.cache.ProductCacheService}); the
 * write-through, write-behind, write-around and invalidation patterns arrive alongside the
 * write endpoint.
 */
@RestController
public class ProductController {

    private final CatalogQueryService catalogQueryService;

    public ProductController(CatalogQueryService catalogQueryService) {
        this.catalogQueryService = catalogQueryService;
    }

    @GetMapping("/api/products/{id}")
    public ResponseEntity<ProductDetails> getProduct(@PathVariable long id) {
        return catalogQueryService.findProductDetails(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
