package com.velox.server.cache;

import com.velox.core.policy.Policy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** No Spring context: this is plain {@code velox-core} usage, so a plain unit test is enough. */
class HotSwappableCacheTest {

    @Test
    void behavesAsAnOrdinaryCacheUntilSwapped() {
        HotSwappableCache<String, String> cache = new HotSwappableCache<>(10, Policy.LRU, 4);

        cache.put("a", "1");

        assertEquals("1", cache.getIfPresent("a"));
        assertEquals(Policy.LRU, cache.currentPolicy());
        assertEquals(10, cache.currentCapacity());
    }

    @Test
    void swapPolicyRebuildsEmptyWithTheNewPolicyAtTheSameCapacity() {
        HotSwappableCache<String, String> cache = new HotSwappableCache<>(10, Policy.LRU, 4);
        cache.put("a", "1");

        cache.swapPolicy(Policy.ARC);

        assertEquals(Policy.ARC, cache.currentPolicy());
        assertEquals(10, cache.currentCapacity());
        assertNull(cache.getIfPresent("a"), "a swap starts the cache empty");
    }

    @Test
    void resizeRebuildsEmptyAtTheNewCapacityWithTheSamePolicy() {
        HotSwappableCache<String, String> cache = new HotSwappableCache<>(10, Policy.W_TINY_LFU, 4);
        cache.put("a", "1");

        cache.resize(500);

        assertEquals(Policy.W_TINY_LFU, cache.currentPolicy());
        assertEquals(500, cache.currentCapacity());
        assertEquals(500, cache.maximumSize());
        assertNull(cache.getIfPresent("a"), "a resize also starts the cache empty");
    }

    @Test
    void aRequestAfterASwapSeesTheNewCacheCleanly() {
        HotSwappableCache<String, String> cache = new HotSwappableCache<>(10, Policy.LRU, 4);
        cache.put("a", "1");

        cache.swapPolicy(Policy.FIFO);
        cache.put("b", "2");

        assertNull(cache.getIfPresent("a"));
        assertEquals("2", cache.getIfPresent("b"));
    }
}
