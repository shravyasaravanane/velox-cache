package com.velox.core.policy;

import com.velox.core.VeloxCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract every {@link EvictionPolicy} must honour, run against <b>every
 * value of the {@link Policy} enum automatically</b>.
 *
 * <p>This is the safety net for Tier 3. When ARC and W-TinyLFU are added to the
 * enum, they are immediately held to these same rules without anyone
 * remembering to write the tests. A new policy that leaks entries, exceeds
 * capacity or fails to reset cleanly is caught before it is ever benchmarked.
 */
class PolicyContractTest {

    private static VeloxCache<String, Integer> cache(Policy policy, int capacity) {
        return new VeloxCache<>(capacity, policy.create(capacity));
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("an empty policy has no victim")
    void emptyPolicyHasNoVictim(Policy policy) {
        EvictionPolicy<String, Integer> fresh = policy.create(10);

        assertNull(fresh.selectVictim());
        fresh.assertInvariants(0);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("the policy's name matches its catalogue entry")
    void nameMatchesCatalogue(Policy policy) {
        assertEquals(policy.displayName(), policy.create(10).name());
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("invalidateAll leaves no trace, and the cache is fully reusable afterwards")
    void clearResetsCompletely(Policy policy) {
        var cache = cache(policy, 5);
        for (int i = 0; i < 20; i++) {
            cache.put("k" + i, i);
            cache.getIfPresent("k" + i);
        }

        cache.invalidateAll();

        assertEquals(0, cache.size());
        // assertInvariants compares the policy's own entry count with the
        // cache's, so this fails if clear() left anything tracked.
        cache.assertInvariants();

        for (int i = 0; i < 20; i++) {
            cache.put("again" + i, i);
        }
        assertEquals(5, cache.size(), "a cleared cache must fill back up to capacity");
        cache.assertInvariants();
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("the cache never exceeds its capacity and never loses track of an entry")
    void neverExceedsCapacityUnderRandomLoad(Policy policy) {
        int capacity = 25;
        var cache = cache(policy, capacity);
        var random = new Random(policy.ordinal() + 100L);

        for (int step = 0; step < 30_000; step++) {
            String key = "k" + random.nextInt(90);
            switch (random.nextInt(4)) {
                case 0, 1 -> {
                    if (cache.getIfPresent(key) == null) {
                        cache.put(key, step);
                    }
                }
                case 2 -> cache.put(key, step);
                default -> cache.invalidate(key);
            }
            assertTrue(cache.size() <= capacity,
                    policy + " exceeded capacity at step " + step + ": " + cache.size());
            if (step % 300 == 0) {
                cache.assertInvariants();
            }
        }
        cache.assertInvariants();
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("a value that was just stored can always be read back")
    void storedValuesAreReadable(Policy policy) {
        var cache = cache(policy, 3);

        for (int i = 0; i < 50; i++) {
            cache.put("k" + i, i);
            // Even a full cache must hold the newest entry: whatever it
            // evicts to make room, it must not be the entry it just admitted.
            assertNotNull(cache.getIfPresent("k" + i),
                    policy + " lost the entry it had just stored (k" + i + ")");
        }
    }
}
