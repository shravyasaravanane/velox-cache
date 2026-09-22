package com.velox.server.cache;

import com.velox.server.repository.CatalogQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plain unit test, not a {@code @SpringBootTest}: {@link CacheVariants} only needs a real
 * {@link CatalogQueryService} over a tiny private database, same pattern as
 * {@link WriteBehindBufferTest}.
 */
class CacheVariantsTest {

    private CacheVariants variants;

    @BeforeEach
    void setUp() {
        var dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:cache-variants-test-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE category (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        jdbcTemplate.execute("CREATE TABLE product (id BIGINT PRIMARY KEY, category_id BIGINT, "
                + "name VARCHAR(200), description VARCHAR(2000), price_cents BIGINT)");
        jdbcTemplate.execute("CREATE TABLE inventory (product_id BIGINT PRIMARY KEY, stock_count INT, "
                + "warehouse_location VARCHAR(50))");
        jdbcTemplate.execute("CREATE TABLE review (id BIGINT PRIMARY KEY, product_id BIGINT, rating INT, "
                + "comment VARCHAR(1000))");
        jdbcTemplate.update("INSERT INTO category (id, name) VALUES (1, 'Books')");
        jdbcTemplate.update("INSERT INTO product (id, category_id, name, description, price_cents) "
                + "VALUES (1, 1, 'Widget', 'd', 999)");
        jdbcTemplate.update("INSERT INTO inventory (product_id, stock_count, warehouse_location) "
                + "VALUES (1, 5, 'A1')");

        CatalogQueryService catalogQueryService = new CatalogQueryService(jdbcTemplate, 0L);
        variants = new CacheVariants(catalogQueryService, 10, "LRU,ARC", 4);
    }

    @Test
    @DisplayName("off never caches: every request reaches the database")
    void offBypassesCachingEntirely() {
        variants.get(CacheVariants.OFF, 1);
        variants.get(CacheVariants.OFF, 1);
        variants.get(CacheVariants.OFF, 1);

        var report = variants.report().get(CacheVariants.OFF);
        assertEquals(3, report.requestCount());
        assertEquals(0.0, report.hitRatePercent());
    }

    @Test
    @DisplayName("a real variant caches: the second request for the same id is a hit")
    void realVariantCachesOnSecondRequest() {
        variants.get("lru", 1);
        variants.get("lru", 1);
        variants.get("lru", 1);

        var report = variants.report().get("lru");
        assertEquals(3, report.requestCount());
        assertTrue(report.hitRatePercent() > 0.0, "two of the three requests should have hit");
    }

    @Test
    @DisplayName("variant names are case-insensitive")
    void variantNameIsCaseInsensitive() {
        variants.get("ARC", 1);
        variants.get("arc", 1);

        assertEquals(2, variants.report().get("arc").requestCount());
    }

    @Test
    @DisplayName("an unconfigured variant name is rejected, not silently treated as off")
    void unknownVariantThrows() {
        assertThrows(IllegalArgumentException.class, () -> variants.get("tinylfu", 1));
    }

    @Test
    @DisplayName("variantNames reports off plus every configured policy")
    void variantNamesIncludesOffAndConfiguredPolicies() {
        assertEquals(java.util.Set.of("off", "lru", "arc"), variants.variantNames());
    }
}
