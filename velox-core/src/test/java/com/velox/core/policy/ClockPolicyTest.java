package com.velox.core.policy;

import com.velox.core.VeloxCache;
import com.velox.core.stats.CacheStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClockPolicyTest {

    private static VeloxCache<String, Integer> cache(int capacity) {
        return new VeloxCache<>(capacity, new ClockPolicy<>());
    }

    @Test
    @DisplayName("with no hits it behaves exactly like FIFO")
    void noHitsMeansFifo() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.put("D", 4);

        assertNull(cache.getIfPresent("A"), "no reference bits set, so the oldest goes");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a referenced entry gets a second chance")
    void referencedEntrySurvivesTheHand() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.getIfPresent("A");   // sets A's reference bit
        cache.put("D", 4);         // hand meets A (spared, bit cleared), then B (evicted)

        assertNotNull(cache.getIfPresent("A"), "A was used, so the hand must skip it once");
        assertNull(cache.getIfPresent("B"), "B was the first unreferenced entry the hand met");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("the second chance is spent: an entry is not protected forever")
    void secondChanceIsConsumed() {
        var cache = cache(2);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.getIfPresent("A");

        cache.put("C", 3);   // spares A (clears its bit), evicts B
        cache.put("D", 4);   // A's bit is now clear and A is the oldest, so A goes

        assertNull(cache.getIfPresent("A"), "without a fresh hit, A must eventually be evicted");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("terminates even when every entry is referenced")
    void allReferencedStillEvictsSomething() {
        var cache = cache(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.getIfPresent("A");
        cache.getIfPresent("B");
        cache.getIfPresent("C");

        cache.put("D", 4);   // the hand must lap the whole ring clearing bits, then evict

        assertEquals(3, cache.size(), "exactly one entry must have been evicted");
        assertEquals(1, cache.stats().evictionCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("matches a naive CLOCK exactly over 100,000 random operations")
    void differentialTest() {
        var stats = PolicyTestSupport.runDifferential(
                "CLOCK", cap -> new ClockPolicy<>(), PolicyTestSupport.NaiveClock::new,
                40, 150, 100_000, 22L);
        System.out.printf("  [CLOCK differential] identical to naive. %s%n", stats);
    }

    @Test
    @DisplayName("CLOCK tracks LRU closely on a skewed workload")
    void approximatesLru() {
        // The claim CLOCK makes: one bit per entry gets close to exact LRU.
        // We measure it rather than assert it on faith.
        double clock = skewedHitRate(new ClockPolicy<>());
        double lru = skewedHitRate(new LruPolicy<>());

        System.out.printf("  [CLOCK vs LRU] skewed workload: CLOCK %.2f%%, LRU %.2f%% (gap %.2f points)%n",
                clock * 100, lru * 100, (lru - clock) * 100);

        assertTrue(Math.abs(lru - clock) < 0.05,
                "CLOCK should stay within 5 points of LRU; got " + clock + " vs " + lru);
    }

    private static double skewedHitRate(EvictionPolicy<String, Integer> policy) {
        var cache = new VeloxCache<String, Integer>(100, policy);
        var random = new Random(5);
        for (int i = 0; i < 200_000; i++) {
            String key = "k" + (int) (1000 * Math.pow(random.nextDouble(), 3.0));
            if (cache.getIfPresent(key) == null) {
                cache.put(key, i);
            }
        }
        CacheStats stats = cache.stats();
        return stats.hitRate();
    }
}
