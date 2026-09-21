package com.velox.core.policy;

import com.velox.core.VeloxCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FifoPolicyTest {

    private static VeloxCache<String, Integer> cache(int capacity) {
        return new VeloxCache<>(capacity, new FifoPolicy<>());
    }

    @Test
    @DisplayName("evicts the oldest entry even if it was just read")
    void hitsDoNotSaveAnEntry() {
        // This single behaviour is the entire difference between FIFO and LRU.
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.getIfPresent("A");   // under LRU this would rescue A
        cache.getIfPresent("A");
        cache.put("D", 4);

        assertNull(cache.getIfPresent("A"), "A was inserted first, so FIFO evicts it despite the reads");
        assertNotNull(cache.getIfPresent("B"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("the same access sequence gives a different survivor under LRU")
    void differsFromLruOnTheSameSequence() {
        var fifo = cache(3);
        var lru = new VeloxCache<String, Integer>(3, new LruPolicy<>());

        runSequence(fifo);
        runSequence(lru);

        assertNull(fifo.getIfPresent("A"), "FIFO evicts A");
        assertNotNull(lru.getIfPresent("A"), "LRU keeps A because it was read");
        assertNull(lru.getIfPresent("B"), "LRU evicts B instead");
    }

    /** put A,B,C then read A then put D -- the sequence where FIFO and LRU disagree. */
    private static void runSequence(VeloxCache<String, Integer> cache) {
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.getIfPresent("A");
        cache.put("D", 4);
    }

    @Test
    @DisplayName("overwriting a key does not refresh its position")
    void overwriteDoesNotRefresh() {
        var cache = cache(2);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.put("A", 99);   // still the oldest insertion
        cache.put("C", 3);

        assertNull(cache.getIfPresent("A"));
        assertEquals(2, cache.getIfPresent("B"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("matches a naive FIFO exactly over 100,000 random operations")
    void differentialTest() {
        var stats = PolicyTestSupport.runDifferential(
                "FIFO", cap -> new FifoPolicy<>(), PolicyTestSupport.NaiveFifo::new,
                40, 150, 100_000, 11L);
        System.out.printf("  [FIFO differential] identical to naive. %s%n", stats);
    }
}
