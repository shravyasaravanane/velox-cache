package com.velox.core;

import com.velox.core.expiry.ExpiryEngineType;
import com.velox.core.expiry.FakeTicker;
import com.velox.core.policy.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builder is the public entry point, so its options are tested through it
 * rather than by constructing {@link VeloxCache} directly.
 */
class CacheBuilderTest {

    // ------------------------------------------------------------------
    //  TTL options, end to end
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ExpiryEngineType.class)
    @DisplayName("expireAfterWrite works through the builder with either expiry engine")
    void expireAfterWriteThroughBuilder(ExpiryEngineType engine) {
        var clock = new FakeTicker();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofSeconds(10))
                .expiryEngine(engine)
                .ticker(clock)
                .build();
        cache.put("A", 1);

        clock.advance(Duration.ofSeconds(10)).advanceNanos(-1);
        assertEquals(1, cache.getIfPresent("A"), "alive 1ns before the deadline");

        clock.advanceNanos(1);
        assertNull(cache.getIfPresent("A"), "dead at the deadline");
    }

    @ParameterizedTest
    @EnumSource(ExpiryEngineType.class)
    @DisplayName("expireAfterAccess works through the builder with either expiry engine")
    void expireAfterAccessThroughBuilder(ExpiryEngineType engine) {
        var clock = new FakeTicker();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(10)
                .expireAfterAccess(Duration.ofSeconds(5))
                .expiryEngine(engine)
                .ticker(clock)
                .build();
        cache.put("A", 1);

        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(4));
            assertEquals(1, cache.getIfPresent("A"), "each read restarts the idle clock");
        }
        clock.advance(Duration.ofSeconds(5));
        assertNull(cache.getIfPresent("A"), "idle for the full timeout");
    }

    @Test
    @DisplayName("timingWheel selects the wheel engine with the given geometry")
    void timingWheelSelectsTheWheel() {
        var clock = new FakeTicker();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofSeconds(3))
                .timingWheel(Duration.ofNanos(500), 4)
                .ticker(clock)
                .build();

        assertEquals("TIMING_WHEEL", ((VeloxCache<String, Integer>) cache).expiryEngineName());
        cache.put("A", 1);
        clock.advance(Duration.ofSeconds(3));
        assertNull(cache.getIfPresent("A"));
    }

    @Test
    @DisplayName("the heap is the default expiry engine")
    void heapIsTheDefault() {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder().build();

        assertEquals("INDEXED_HEAP", ((VeloxCache<String, Integer>) cache).expiryEngineName());
    }

    @Test
    @DisplayName("jitter set through the builder spreads expiry")
    void jitterThroughBuilder() {
        var clock = new FakeTicker();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(100))
                .ttlJitter(0.2)
                .ticker(clock)
                .build();
        for (int i = 0; i < 500; i++) {
            cache.put("k" + i, i);
        }

        clock.advance(Duration.ofSeconds(79));       // before the earliest possible (80s)
        cache.cleanUp();
        assertEquals(500, cache.size());

        clock.advance(Duration.ofSeconds(42));       // past the latest possible (120s)
        cache.cleanUp();
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("the chosen eviction policy is the one the cache uses")
    void policyThroughBuilder() {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(3)
                .policy(Policy.FIFO)
                .build();
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.getIfPresent("A");                     // would rescue A under LRU, not under FIFO
        cache.put("D", 4);

        assertNull(cache.getIfPresent("A"), "FIFO evicts the oldest insertion regardless of reads");
        assertEquals("FIFO", ((VeloxCache<String, Integer>) cache).policyName());
    }

    // ------------------------------------------------------------------
    //  Validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("non-positive or missing durations are rejected")
    void rejectsBadDurations() {
        var builder = CacheBuilder.<String, Integer>newBuilder();

        assertThrows(IllegalArgumentException.class, () -> builder.expireAfterWrite(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.expireAfterWrite(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> builder.expireAfterAccess(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.expireAfterAccess(Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> builder.expireAfterWrite(null));
        assertThrows(NullPointerException.class, () -> builder.expireAfterAccess(null));
    }

    @Test
    @DisplayName("jitter outside [0, 1) is rejected")
    void rejectsBadJitter() {
        var builder = CacheBuilder.<String, Integer>newBuilder();

        assertThrows(IllegalArgumentException.class, () -> builder.ttlJitter(-0.1));
        assertThrows(IllegalArgumentException.class, () -> builder.ttlJitter(1.0));
        assertThrows(IllegalArgumentException.class, () -> builder.ttlJitter(1.5));
        builder.ttlJitter(0);                        // the boundary values that ARE allowed
        builder.ttlJitter(0.999);
    }

    @Test
    @DisplayName("invalid timing-wheel geometry is rejected")
    void rejectsBadWheelGeometry() {
        var builder = CacheBuilder.<String, Integer>newBuilder();

        assertThrows(IllegalArgumentException.class, () -> builder.timingWheel(Duration.ZERO, 8));
        assertThrows(IllegalArgumentException.class, () -> builder.timingWheel(Duration.ofMillis(1), 6));
        assertThrows(IllegalArgumentException.class, () -> builder.timingWheel(Duration.ofMillis(1), 1));
    }

    @Test
    @DisplayName("null options are rejected")
    void rejectsNulls() {
        var builder = CacheBuilder.<String, Integer>newBuilder();

        assertThrows(NullPointerException.class, () -> builder.policy(null));
        assertThrows(NullPointerException.class, () -> builder.ticker(null));
        assertThrows(NullPointerException.class, () -> builder.expiryEngine(null));
        assertThrows(NullPointerException.class, () -> builder.weigher(null));
    }

    @Test
    @DisplayName("Capacity rejects nonsensical budgets")
    void capacityValidation() {
        assertThrows(IllegalArgumentException.class, () -> Capacity.entries(0));
        assertThrows(IllegalArgumentException.class, () -> Capacity.<String, Integer>weighted(0, (k, v) -> 1, 16));
        assertThrows(NullPointerException.class, () -> Capacity.<String, Integer>weighted(10, null, 16));
        assertThrows(IllegalArgumentException.class, () -> new Capacity<String, Integer>(10, null, 10, 0));

        assertTrue(Capacity.<String, Integer>entries(5).isCountBounded());
        assertTrue(!Capacity.<String, Integer>weighted(10, (k, v) -> 1, 16).isCountBounded());
    }

    @Test
    @DisplayName("an expectedEntries hint is accepted for a weight-bounded cache")
    void expectedEntriesHint() {
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumWeight(1_000)
                .weigher((key, value) -> value)
                .expectedEntries(50)
                .policy(Policy.LFU_AGED)             // the policy that sizes itself from the hint
                .build();

        cache.put("A", 10);

        assertEquals(10, cache.weightedSize());
    }
}
