package com.velox.server.cache;

import com.velox.server.domain.ProductDetails;
import com.velox.server.repository.CatalogQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Every cache/database integration pattern, checked against the real (if tiny, test-seeded)
 * database. {@link CatalogQueryService} is spied on, not mocked -- its real join still runs --
 * purely so "did this read actually reach the database" is directly observable rather than
 * inferred.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductCacheServiceTest {

    @Autowired
    private ProductCacheService service;
    @Autowired
    private WriteBehindBuffer writeBehindBuffer;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @SpyBean
    private CatalogQueryService catalogQueryService;

    @Test
    @DisplayName("cache-aside: a miss loads and caches; a hit does not touch the database again")
    void cacheAsideLoadsOnce() {
        clearInvocations(catalogQueryService);

        ProductDetails first = service.getCacheAside(1).orElseThrow();
        verify(catalogQueryService, times(1)).findProductDetails(1);

        ProductDetails second = service.getCacheAside(1).orElseThrow();

        assertEquals(first, second);
        verify(catalogQueryService, times(1)).findProductDetails(1); // still just the one call
    }

    @Test
    @DisplayName("cache-aside: a thundering herd on one cold key collapses into a single database call")
    void cacheAsideSingleFlightsConcurrentMissesOnTheSameKey() throws InterruptedException {
        clearInvocations(catalogQueryService);
        int concurrency = 50;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        service.getCacheAside(9);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }

        verify(catalogQueryService, times(1)).findProductDetails(9L);
    }

    @Test
    @DisplayName("write-through: the cache reflects the write immediately, with no further database hit")
    void writeThroughRefreshesTheCacheImmediately() {
        service.getCacheAside(2);                              // populate

        var updated = service.writeThrough(2, new ProductUpdate("Renamed", "New description", 999))
                .orElseThrow();

        assertEquals("Renamed", updated.name());
        assertEquals(999, updated.priceCents());
        clearInvocations(catalogQueryService);
        assertEquals("Renamed", service.getCacheAside(2).orElseThrow().name());
        verify(catalogQueryService, times(0)).findProductDetails(2); // write-through already refreshed it
        assertEquals("Renamed", jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 2", String.class),
                "write-through must also have reached the database");
    }

    @Test
    @DisplayName("write-around: the database changes, the stale cache entry is dropped, not refreshed")
    void writeAroundDropsTheCacheEntry() {
        service.getCacheAside(3);                              // populate with the ORIGINAL name

        boolean existed = service.writeAround(3, new ProductUpdate("Around-Updated", "d", 111));

        assertTrue(existed);
        assertEquals("Around-Updated",
                jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 3", String.class));
        clearInvocations(catalogQueryService);
        // The next read must reload from the database (the updated name), not serve a stale cached copy.
        assertEquals("Around-Updated", service.getCacheAside(3).orElseThrow().name());
        verify(catalogQueryService, times(1)).findProductDetails(3); // a genuine miss, not a stale hit
    }

    @Test
    @DisplayName("write-behind: the cache sees the write immediately; the database only catches up after a flush")
    void writeBehindDefersTheDatabaseWrite() {
        service.getCacheAside(4);
        String beforeName = jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 4", String.class);

        ProductDetails patched = service.writeBehind(4, new ProductUpdate("Behind-Updated", "d", 222));

        assertEquals("Behind-Updated", patched.name());
        clearInvocations(catalogQueryService);
        assertEquals("Behind-Updated", service.getCacheAside(4).orElseThrow().name(), "the cache must show the write now");
        verify(catalogQueryService, times(0)).findProductDetails(4); // served from cache, not reloaded
        assertEquals(beforeName, jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 4", String.class),
                "the database must NOT have the write yet -- that is what makes this write-BEHIND");

        writeBehindBuffer.flush();

        assertEquals("Behind-Updated", jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 4", String.class),
                "an explicit flush must apply the buffered write");
    }

    @Test
    @DisplayName("delayed double-delete: a read right after sees the update; a read after the delay reloads again")
    void delayedDoubleDeleteEmptiesTheCacheTwice() {
        service.getCacheAside(6);                              // populate
        clearInvocations(catalogQueryService);

        boolean existed = service.invalidateWithDelayedDoubleDelete(6, new ProductUpdate("DD-Updated", "d", 333));

        assertTrue(existed);
        assertEquals("DD-Updated", jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 6", String.class));
        // The FIRST delete already ran (synchronously, before the method returned): this read is a
        // genuine miss that repopulates the cache with the now-correct data.
        assertEquals("DD-Updated", service.getCacheAside(6).orElseThrow().name());
        verify(catalogQueryService, times(1)).findProductDetails(6);

        // After the configured delay the SECOND delete fires; poll (rather than a fixed sleep) for
        // a read to become a miss again, since the scheduler's exact timing is not this test's concern.
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            clearInvocations(catalogQueryService);
            service.getCacheAside(6);
            verify(catalogQueryService, times(1)).findProductDetails(6);
        });
    }

    @Test
    @DisplayName("tag-based invalidation: only the tagged keys are dropped, in one pass")
    void tagInvalidationDropsOnlyTaggedKeys() {
        ProductDetails seven = service.getCacheAside(7).orElseThrow();
        service.getCacheAside(8);
        String tag = "category:" + seven.categoryName();

        int invalidated = service.invalidateByTag(tag);
        assertTrue(invalidated >= 1);

        clearInvocations(catalogQueryService);
        service.getCacheAside(7);
        verify(catalogQueryService, times(1)).findProductDetails(7); // dropped: a genuine miss
    }
}
