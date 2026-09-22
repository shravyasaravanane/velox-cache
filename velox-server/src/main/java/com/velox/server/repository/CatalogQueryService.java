package com.velox.server.repository;

import com.velox.server.domain.ProductDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The one query in this application deliberately built to be <b>expensive</b>, because this
 * tier's whole point is to make a cache's benefit visible and measurable.
 *
 * <h2>Why this is a hand-written join, not a JPA association graph</h2>
 *
 * A single product's detail page needs its category name, its stock, and an aggregate over
 * every review it has ever received — four tables, one of them (review) potentially large
 * per product, and the aggregate cannot be served by an index alone. That is a realistic
 * shape for "the expensive read a cache exists to avoid repeating": a search or a list
 * endpoint would touch an index and return quickly; a detail page with reviews genuinely
 * cannot, at any real scale.
 *
 * <h2>The artificial latency knob</h2>
 *
 * An embedded H2 database sitting in the same JVM answers even a real join in well under a
 * millisecond — nothing like a production database a network hop away. {@code
 * velox.demo.db-latency-ms} adds a deliberate, honest delay on top of the real query, so the
 * effect a cache has is visible and tunable in a live demo. This is disclosed here, in the
 * Javadoc, and again in {@code docs/} — the number is a simulation of round-trip cost, not a
 * claim about how slow this specific join actually is.
 */
@Repository
public class CatalogQueryService {

    private static final String FIND_PRODUCT_DETAILS = """
            SELECT p.id AS product_id, p.name, p.description, p.price_cents,
                   c.name AS category_name,
                   i.stock_count, i.warehouse_location,
                   COALESCE(AVG(r.rating), 0) AS avg_rating,
                   COUNT(r.id) AS review_count
            FROM product p
            JOIN category c ON c.id = p.category_id
            JOIN inventory i ON i.product_id = p.id
            LEFT JOIN review r ON r.product_id = p.id
            WHERE p.id = ?
            GROUP BY p.id, p.name, p.description, p.price_cents, c.name, i.stock_count, i.warehouse_location
            """;

    private final JdbcTemplate jdbcTemplate;
    private final long simulatedLatencyMs;

    public CatalogQueryService(JdbcTemplate jdbcTemplate,
            @Value("${velox.demo.db-latency-ms:0}") long simulatedLatencyMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.simulatedLatencyMs = simulatedLatencyMs;
    }

    /** @return the product's full detail-page data, or empty if no product has this id */
    public Optional<ProductDetails> findProductDetails(long productId) {
        simulateNetworkHop();
        List<ProductDetails> rows = jdbcTemplate.query(FIND_PRODUCT_DETAILS, (rs, rowNum) -> new ProductDetails(
                rs.getLong("product_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("price_cents"),
                rs.getString("category_name"),
                rs.getInt("stock_count"),
                rs.getString("warehouse_location"),
                rs.getDouble("avg_rating"),
                rs.getLong("review_count")), productId);
        return rows.stream().findFirst();
    }

    /** @return whether a product with this id exists, without the aggregate cost of the full join */
    public boolean exists(long productId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product WHERE id = ?", Integer.class, productId);
        return count != null && count > 0;
    }

    private void simulateNetworkHop() {
        if (simulatedLatencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(simulatedLatencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while simulating DB latency", e);
        }
    }
}
