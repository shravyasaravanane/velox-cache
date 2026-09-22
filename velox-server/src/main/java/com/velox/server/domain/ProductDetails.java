package com.velox.server.domain;

/**
 * The result of {@code CatalogQueryService}'s join: everything a product's detail page
 * needs, assembled from four tables in one query rather than four round trips.
 *
 * <p>This is exactly the object every caching pattern in this tier stores, keyed by
 * {@code productId} — the unit of "one expensive thing worth not repeating".
 */
public record ProductDetails(
        long productId,
        String name,
        String description,
        long priceCents,
        String categoryName,
        int stockCount,
        String warehouseLocation,
        double averageRating,
        long reviewCount) {
}
