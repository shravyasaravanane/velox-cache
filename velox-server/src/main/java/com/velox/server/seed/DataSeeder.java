package com.velox.server.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Fills an empty database with a synthetic catalog, so the demo has something realistic to
 * be slow about. Runs once, on startup, and is a no-op on every later restart — it checks
 * whether {@code product} already has rows before doing anything.
 *
 * <h2>Why raw batched JDBC, not JPA saves</h2>
 *
 * Saving a million {@code Product} entities one at a time through Hibernate — flushing the
 * persistence context, tracking each managed entity — would make seeding itself the slowest
 * part of running this demo. {@link JdbcTemplate#batchUpdate} sends rows in chunks with no
 * per-row object tracking at all, which is the same reason production ETL jobs never use an
 * ORM for bulk loads. The rest of this application uses JPA throughout; only seeding bypasses
 * it, and only for this reason.
 *
 * <h2>The shape of the data</h2>
 *
 * {@code velox.demo.seed-products} products (1,000,000 by default — lower it for a faster
 * local run, e.g. {@code --velox.demo.seed-products=5000}), each in one of a small fixed set
 * of categories, each with exactly one inventory row, and each with a random 0-20 reviews
 * (averaging around 5) — enough that {@code CatalogQueryService}'s aggregate join has a real
 * table to scan, not a handful of rows an index would flatten to nothing.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final int BATCH_SIZE = 1_000;

    private static final String[] CATEGORY_NAMES = {
            "Electronics", "Home & Kitchen", "Books", "Sports & Outdoors", "Toys & Games",
            "Clothing", "Beauty", "Automotive", "Garden", "Office Supplies",
            "Pet Supplies", "Musical Instruments", "Health", "Grocery", "Tools",
            "Video Games", "Baby", "Jewelry", "Shoes", "Luggage",
    };
    private static final String[] WAREHOUSES = {"US-EAST", "US-WEST", "EU-CENTRAL", "AP-SOUTH"};
    private static final String[] ADJECTIVES = {
            "Compact", "Premium", "Portable", "Wireless", "Ergonomic", "Durable", "Eco-Friendly",
            "Smart", "Classic", "Professional", "Lightweight", "Heavy-Duty", "Rechargeable",
    };
    private static final String[] NOUNS = {
            "Widget", "Gadget", "Organizer", "Charger", "Backpack", "Speaker", "Lamp", "Bottle",
            "Case", "Stand", "Kit", "Set", "Monitor", "Chair", "Blanket", "Mug",
    };

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final long seedProducts;
    private final long seed;

    public DataSeeder(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            @Value("${velox.demo.seed-products:1000000}") long seedProducts,
            @Value("${velox.demo.seed-random-seed:42}") long seed) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.seedProducts = seedProducts;
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Long.class);
        if (existing != null && existing > 0) {
            log.info("Catalog already seeded ({} products); skipping.", existing);
            return;
        }

        long startNanos = System.nanoTime();
        var random = new SplittableRandom(seed);

        List<Long> categoryIds = seedCategories();
        log.info("Seeding {} products (this can take a while at the default 1,000,000)...", seedProducts);
        seedProductsInventoryAndReviews(categoryIds, random);

        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        log.info("Seed complete in {} s.", String.format("%.1f", seconds));
    }

    private List<Long> seedCategories() {
        for (String name : CATEGORY_NAMES) {
            jdbcTemplate.update("INSERT INTO category (name) VALUES (?)", name);
        }
        return jdbcTemplate.queryForList("SELECT id FROM category ORDER BY id", Long.class);
    }

    private void seedProductsInventoryAndReviews(List<Long> categoryIds, SplittableRandom random) {
        List<Object[]> productBatch = new ArrayList<>(BATCH_SIZE);
        List<Object[]> inventoryBatch = new ArrayList<>(BATCH_SIZE);
        List<Object[]> reviewBatch = new ArrayList<>(BATCH_SIZE * 5);

        for (long i = 0; i < seedProducts; i++) {
            long categoryId = categoryIds.get(random.nextInt(categoryIds.size()));
            String name = ADJECTIVES[random.nextInt(ADJECTIVES.length)] + " " + NOUNS[random.nextInt(NOUNS.length)]
                    + " #" + (i + 1);
            String description = "A " + name.toLowerCase(Locale.ROOT)
                    + ", generated for the VeloxCache demo catalog.";
            long priceCents = 500 + random.nextInt(49_500);
            productBatch.add(new Object[] {categoryId, name, description, priceCents});

            long productId = i + 1; // IDENTITY columns here start at 1 and increment by row order
            inventoryBatch.add(new Object[] {productId, random.nextInt(2_000), WAREHOUSES[random.nextInt(WAREHOUSES.length)]});

            int reviewCount = random.nextInt(21);
            for (int r = 0; r < reviewCount; r++) {
                int rating = 1 + random.nextInt(5);
                reviewBatch.add(new Object[] {productId, rating, "Review " + (r + 1) + " for product " + productId});
            }

            if (productBatch.size() == BATCH_SIZE) {
                flush(productBatch, inventoryBatch, reviewBatch);
            }
            if ((i + 1) % 100_000 == 0) {
                log.info("  ...{} products seeded", i + 1);
            }
        }
        flush(productBatch, inventoryBatch, reviewBatch);
    }

    /**
     * Product ids are auto-generated by the database, so {@link #seedProductsInventoryAndReviews}
     * cannot know a product's real id before insertion -- except that H2 and Postgres IDENTITY
     * columns are both guaranteed sequential from 1 in insertion order for a table nothing else
     * writes to concurrently, which a single-threaded seeder on a fresh table satisfies exactly.
     * Batching all three tables together, in one transaction per batch, keeps that assumption
     * safe: a batch either commits as a whole (ids and their inventory/reviews stay in step) or
     * not at all.
     */
    private void flush(List<Object[]> productBatch, List<Object[]> inventoryBatch, List<Object[]> reviewBatch) {
        if (productBatch.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO product (category_id, name, description, price_cents) VALUES (?, ?, ?, ?)",
                    productBatch);
            jdbcTemplate.batchUpdate(
                    "INSERT INTO inventory (product_id, stock_count, warehouse_location) VALUES (?, ?, ?)",
                    inventoryBatch);
            if (!reviewBatch.isEmpty()) {
                jdbcTemplate.batchUpdate(
                        "INSERT INTO review (product_id, rating, comment) VALUES (?, ?, ?)",
                        reviewBatch);
            }
        });
        productBatch.clear();
        inventoryBatch.clear();
        reviewBatch.clear();
    }
}
