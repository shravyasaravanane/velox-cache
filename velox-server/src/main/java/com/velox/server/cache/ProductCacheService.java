package com.velox.server.cache;

import com.velox.core.Cache;
import com.velox.server.domain.ProductDetails;
import com.velox.server.repository.CatalogQueryService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Every cache/database integration pattern this tier exists to demonstrate, over the same
 * {@link Cache} and the same {@link CatalogQueryService} join. Each method below is a
 * complete, independent pattern — the controller picks one per write, so every pattern stays
 * separately reachable and separately testable, rather than folded into one "smart" method
 * that hides which trade-off is in effect.
 */
@Service
public class ProductCacheService {

    private final Cache<Long, ProductDetails> cache;
    private final CatalogQueryService catalogQueryService;
    private final JdbcTemplate jdbcTemplate;
    private final WriteBehindBuffer writeBehindBuffer;
    private final TagIndex tagIndex;
    private final ScheduledExecutorService doubleDeleteScheduler;
    private final long doubleDeleteDelayMs;

    public ProductCacheService(Cache<Long, ProductDetails> cache, CatalogQueryService catalogQueryService,
            JdbcTemplate jdbcTemplate, WriteBehindBuffer writeBehindBuffer, TagIndex tagIndex,
            @Value("${velox.demo.double-delete-delay-ms:500}") long doubleDeleteDelayMs) {
        this.cache = cache;
        this.catalogQueryService = catalogQueryService;
        this.jdbcTemplate = jdbcTemplate;
        this.writeBehindBuffer = writeBehindBuffer;
        this.tagIndex = tagIndex;
        this.doubleDeleteDelayMs = doubleDeleteDelayMs;
        ThreadFactory daemonFactory = runnable -> {
            Thread thread = new Thread(runnable, "velox-double-delete");
            thread.setDaemon(true);
            return thread;
        };
        this.doubleDeleteScheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory);
    }

    @PreDestroy
    void shutdown() {
        doubleDeleteScheduler.shutdown();
    }

    // ------------------------------------------------------------------
    //  Read: cache-aside
    // ------------------------------------------------------------------

    /**
     * The default read path: check the cache; on a miss, run the real join and populate the
     * cache for next time. This is the pattern {@code GET /api/products/{id}} uses, and the
     * one {@code docs/SYSTEM.md}'s headline numbers are measured against.
     *
     * <p>Built on {@link Cache#get(Object, java.util.function.Function)}, not a manual
     * getIfPresent-then-put, specifically so a thundering herd on one cold key -- {@code
     * POST /api/chaos/stampede} exists to demonstrate exactly this -- collapses into a single
     * database call: every thread misses on {@code getIfPresent} sees the same in-flight load
     * and waits for its result, rather than each starting its own identical query.
     */
    public Optional<ProductDetails> getCacheAside(long id) {
        ProductDetails details = cache.get(id, key -> catalogQueryService.findProductDetails(key)
                .map(loaded -> {
                    tagIndex.index(key, categoryTag(loaded));
                    return loaded;
                })
                .orElse(null));
        return Optional.ofNullable(details);
    }

    // ------------------------------------------------------------------
    //  Write-through
    // ------------------------------------------------------------------

    /**
     * Writes the database, then immediately re-runs the join and refreshes the cache with the
     * result — the next read is guaranteed both fast and correct, at the cost of paying the
     * full join's cost on every write, not just every miss.
     *
     * @return the fresh, cached value, or empty if {@code id} does not exist
     */
    public Optional<ProductDetails> writeThrough(long id, ProductUpdate update) {
        int rowsUpdated = updateProductRow(id, update);
        if (rowsUpdated == 0) {
            return Optional.empty();
        }
        Optional<ProductDetails> fresh = catalogQueryService.findProductDetails(id);
        fresh.ifPresent(details -> populate(id, details));
        return fresh;
    }

    // ------------------------------------------------------------------
    //  Write-around
    // ------------------------------------------------------------------

    /**
     * Writes the database only. Any cached entry is invalidated (never left stale), but the
     * cache is <b>not</b> proactively refreshed — the next reader pays a cache-aside miss.
     * Appropriate when writes to a key are far more common than reads of it: write-through's
     * "refresh on every write" would mostly cache values nobody asks for before they change
     * again.
     *
     * @return whether a product with this id existed to update
     */
    public boolean writeAround(long id, ProductUpdate update) {
        int rowsUpdated = updateProductRow(id, update);
        if (rowsUpdated > 0) {
            cache.invalidate(id);
        }
        return rowsUpdated > 0;
    }

    // ------------------------------------------------------------------
    //  Write-behind
    // ------------------------------------------------------------------

    /**
     * Updates the cache <b>immediately</b> — a caller's own write is visible to their own
     * very next read, with no wait — and defers the database write to {@link WriteBehindBuffer}'s
     * periodic batch flush, which coalesces repeated writes to the same key into one.
     *
     * <p>The cache entry is built by patching the fields this update changes onto whatever is
     * currently known (cached, or freshly loaded if not), <b>not</b> by re-running the join:
     * the database does not have this write yet, so re-querying it would either return stale
     * data or race the flush that is about to catch it up. This is the one pattern here where
     * the cache is briefly the more current copy of the two.
     *
     * @return the product as the cache now shows it, ahead of the database
     */
    public ProductDetails writeBehind(long id, ProductUpdate update) {
        ProductDetails base = cache.getIfPresent(id);
        if (base == null) {
            base = catalogQueryService.findProductDetails(id)
                    .orElseThrow(() -> new NoSuchElementException("no such product: " + id));
        }
        ProductDetails patched = new ProductDetails(id, update.name(), update.description(), update.priceCents(),
                base.categoryName(), base.stockCount(), base.warehouseLocation(),
                base.averageRating(), base.reviewCount());
        populate(id, patched);
        writeBehindBuffer.stage(id, update);
        return patched;
    }

    // ------------------------------------------------------------------
    //  Invalidation on mutation: delayed double-delete
    // ------------------------------------------------------------------

    /**
     * The classic cache-invalidation race, and the classic partial fix for it.
     *
     * <p><b>The race:</b> a plain "write DB, then delete cache" has a window where a reader
     * that misses the cache <i>before</i> the delete, but populates it <i>after</i>, caches a
     * value that is already stale by the time it lands — and nothing ever corrects it until
     * the entry's own expiry or the next write. Deleting the cache <i>before</i> the write (as
     * this method does first) avoids populating it with the pre-write value, but opens a
     * narrower version of the same race: a read that started just before the delete can still
     * finish populating the cache with the stale value just after it.
     *
     * <p><b>What the second, delayed delete closes:</b> if {@code doubleDeleteDelayMs} is
     * longer than that read can plausibly take, the second delete removes whatever stale
     * value snuck in during the narrow window above, and the next reader repopulates
     * correctly from the now-updated database.
     *
     * <p><b>What it does not close:</b> two overlapping writers to the <i>same</i> key racing
     * each other (their two delayed deletes can interleave in either order); a reader whose
     * populate takes <i>longer</i> than the delay; or anything downstream of a read replica
     * whose replication lag exceeds the delay. None of these are exotic — they are the
     * documented reason delayed double-delete is a mitigation, not a guarantee, and why a
     * system that cannot tolerate any staleness needs a stronger mechanism than this one.
     */
    public boolean invalidateWithDelayedDoubleDelete(long id, ProductUpdate update) {
        cache.invalidate(id);
        int rowsUpdated = updateProductRow(id, update);
        doubleDeleteScheduler.schedule(() -> cache.invalidate(id), doubleDeleteDelayMs, TimeUnit.MILLISECONDS);
        return rowsUpdated > 0;
    }

    // ------------------------------------------------------------------
    //  Tag-based invalidation
    // ------------------------------------------------------------------

    /** @return how many cached products were invalidated */
    public int invalidateByTag(String tag) {
        Set<Long> keys = tagIndex.keysForTag(tag);
        keys.forEach(cache::invalidate);
        return keys.size();
    }

    // ------------------------------------------------------------------

    private void populate(long id, ProductDetails details) {
        cache.put(id, details);
        tagIndex.index(id, categoryTag(details));
    }

    private static String categoryTag(ProductDetails details) {
        return "category:" + details.categoryName();
    }

    private int updateProductRow(long id, ProductUpdate update) {
        return jdbcTemplate.update("UPDATE product SET name = ?, description = ?, price_cents = ? WHERE id = ?",
                update.name(), update.description(), update.priceCents(), id);
    }
}
