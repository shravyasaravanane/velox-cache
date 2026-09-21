package com.velox.core;

import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.FakeTicker;
import com.velox.core.expiry.HeapExpiryEngine;
import com.velox.core.policy.EvictionPolicy;
import com.velox.core.policy.LruPolicy;
import com.velox.core.policy.Policy;
import com.velox.core.structure.Node;
import com.velox.core.util.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeloxCacheRemovalListenerTest {

    /** One recorded notification. */
    private record Event(String key, Object value, RemovalCause cause) {
    }

    private static VeloxCache<String, Integer> cache(int capacity, RemovalListener<String, Integer> listener) {
        return new VeloxCache<>(Capacity.entries(capacity), new LruPolicy<>(),
                ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>(), listener);
    }

    /** A weight-bounded cache where the value is its own weight. */
    private static VeloxCache<String, Integer> weighted(long budget, RemovalListener<String, Integer> listener) {
        return new VeloxCache<>(Capacity.<String, Integer>weighted(budget, (k, v) -> v, 16), new LruPolicy<>(),
                ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>(), listener);
    }

    private static VeloxCache<String, Integer> timed(FakeTicker clock, RemovalListener<String, Integer> listener) {
        return new VeloxCache<>(Capacity.entries(10), new LruPolicy<>(),
                new ExpiryConfig(Duration.ofSeconds(10).toNanos(), -1, 0), clock, new HeapExpiryEngine<>(), listener);
    }

    // ------------------------------------------------------------------
    //  Each cause
    // ------------------------------------------------------------------

    @Test
    @DisplayName("EXPLICIT: invalidate reports the removed value; a missing key reports nothing")
    void explicitInvalidate() {
        var events = new ArrayList<Event>();
        var cache = cache(5, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 1);

        cache.invalidate("A");
        cache.invalidate("ghost");

        assertEquals(List.of(new Event("A", 1, RemovalCause.EXPLICIT)), events);
    }

    @Test
    @DisplayName("EXPLICIT: invalidateAll reports every entry")
    void explicitInvalidateAll() {
        var events = new ArrayList<Event>();
        var cache = cache(5, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.invalidateAll();

        assertEquals(Set.of(new Event("A", 1, RemovalCause.EXPLICIT), new Event("B", 2, RemovalCause.EXPLICIT),
                new Event("C", 3, RemovalCause.EXPLICIT)), new HashSet<>(events));
        assertEquals(3, events.size(), "each entry is reported exactly once");
        assertEquals(0, cache.size());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("REPLACED: overwriting a key reports the OLD value")
    void replacedReportsTheOldValue() {
        var events = new ArrayList<Event>();
        var cache = cache(5, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 1);

        cache.put("A", 2);

        assertEquals(List.of(new Event("A", 1, RemovalCause.REPLACED)), events);
        assertEquals(2, cache.getIfPresent("A"));
    }

    @Test
    @DisplayName("REPLACED: storing the very same object again reports nothing, because nothing left")
    void replacingWithTheSameObjectIsSilent() {
        var events = new ArrayList<Event>();
        var cache = new VeloxCache<String, String>(Capacity.entries(5), new LruPolicy<>(), ExpiryConfig.NONE,
                Ticker.system(), new HeapExpiryEngine<>(), (k, v, c) -> events.add(new Event(k, v, c)));
        String value = new String("same");

        cache.put("A", value);
        cache.put("A", value);

        assertTrue(events.isEmpty(), events.toString());
    }

    @Test
    @DisplayName("REPLACED: a heavier overwrite (remove-and-reinsert) still reports the old value once")
    void heavierOverwriteReportsOldValue() {
        var events = new ArrayList<Event>();
        var cache = weighted(10, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 3);

        cache.put("A", 6);

        assertEquals(List.of(new Event("A", 3, RemovalCause.REPLACED)), events);
        assertEquals(6, cache.getIfPresent("A"));
    }

    @Test
    @DisplayName("SIZE: an evicted entry is reported with its own value")
    void sizeEviction() {
        var events = new ArrayList<Event>();
        var cache = cache(2, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 1);
        cache.put("B", 2);

        cache.put("C", 3);

        assertEquals(List.of(new Event("A", 1, RemovalCause.SIZE)), events);
    }

    @Test
    @DisplayName("SIZE: one large entry reports every victim, in eviction order")
    void multipleVictimsAreReportedInOrder() {
        var events = new ArrayList<Event>();
        var cache = weighted(10, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 2);
        cache.put("B", 2);
        cache.put("C", 2);
        cache.put("D", 2);

        cache.put("E", 7);                     // evicts A, B, C in LRU order

        assertEquals(List.of(new Event("A", 2, RemovalCause.SIZE), new Event("B", 2, RemovalCause.SIZE),
                new Event("C", 2, RemovalCause.SIZE)), events);
    }

    @Test
    @DisplayName("SIZE: an entry too heavy for the whole cache is reported, though it was never stored")
    void oversizedEntryIsReported() {
        var events = new ArrayList<Event>();
        var cache = weighted(10, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 4);

        cache.put("HUGE", 11);

        assertEquals(List.of(new Event("HUGE", 11, RemovalCause.SIZE)), events,
                "the cache took it and did not keep it, so a resource-closing listener must hear about it");
        assertEquals(4, cache.getIfPresent("A"), "and nothing that WAS stored is disturbed");
    }

    @Test
    @DisplayName("SIZE: an entry refused by the admission policy is reported")
    void admissionRefusalIsReported() {
        var events = new ArrayList<Event>();
        var cache = new VeloxCache<String, Integer>(Capacity.entries(1), new RejectingPolicy<>(),
                ExpiryConfig.NONE, Ticker.system(), new HeapExpiryEngine<>(),
                (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 1);

        cache.put("B", 2);                     // full, and the policy refuses newcomers

        assertEquals(List.of(new Event("B", 2, RemovalCause.SIZE)), events);
        assertEquals(1, cache.getIfPresent("A"), "the incumbent stays");
        assertEquals(1, cache.stats().rejectionCount());
        assertEquals(0, cache.stats().evictionCount(), "a refusal is not an eviction");
    }

    @Test
    @DisplayName("a heavier overwrite that cannot fit reports both the old value and the refused new one")
    void heavierOverwriteBeyondCapacity() {
        var events = new ArrayList<Event>();
        var cache = weighted(10, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 3);

        cache.put("A", 11);

        assertEquals(List.of(new Event("A", 3, RemovalCause.REPLACED), new Event("A", 11, RemovalCause.SIZE)), events);
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("EXPIRED: a lazily expired entry is reported once, after the read finishes")
    void lazyExpiry() {
        var clock = new FakeTicker();
        var events = new ArrayList<Event>();
        var cache = timed(clock, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 1);
        clock.advance(Duration.ofSeconds(11));

        assertEquals(null, cache.getIfPresent("A"));
        assertEquals(null, cache.getIfPresent("A"));

        assertEquals(List.of(new Event("A", 1, RemovalCause.EXPIRED)), events, "reported once, not once per read");
    }

    @Test
    @DisplayName("EXPIRED: a put sweeps and reports entries that timed out")
    void sweepOnPut() {
        var clock = new FakeTicker();
        var events = new ArrayList<Event>();
        var cache = timed(clock, (k, v, c) -> events.add(new Event(k, v, c)));
        cache.put("A", 1);
        clock.advance(Duration.ofSeconds(11));

        cache.put("B", 2);

        assertEquals(List.of(new Event("A", 1, RemovalCause.EXPIRED)), events);
    }

    @Test
    @DisplayName("EXPIRED: cleanUp reports every due entry")
    void cleanUpReports() {
        var clock = new FakeTicker();
        var events = new ArrayList<Event>();
        var cache = timed(clock, (k, v, c) -> events.add(new Event(k, v, c)));
        for (int i = 0; i < 5; i++) {
            cache.put("k" + i, i);
        }
        clock.advance(Duration.ofSeconds(11));

        cache.cleanUp();

        assertEquals(5, events.size());
        assertTrue(events.stream().allMatch(e -> e.cause() == RemovalCause.EXPIRED));
    }

    // ------------------------------------------------------------------
    //  When and how listeners run
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a listener runs only after the operation has finished: it sees the new entry and a consistent cache")
    void listenerSeesAConsistentCache() {
        var holder = new AtomicReference<VeloxCache<String, Integer>>();
        var observations = new ArrayList<String>();
        var cache = cache(2, (k, v, c) -> {
            VeloxCache<String, Integer> self = holder.get();
            self.assertInvariants();                                  // would throw on a half-finished update
            observations.add(k + ": evicted key present=" + self.containsKey(k)
                    + ", newcomer present=" + self.containsKey("C") + ", size=" + self.size());
        });
        holder.set(cache);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.put("C", 3);                                           // evicts A

        assertEquals(List.of("A: evicted key present=false, newcomer present=true, size=2"), observations,
                "a listener fired mid-update would not yet see the newcomer");
    }

    @Test
    @DisplayName("a listener may call back into the cache; its removals are delivered after it returns, never nested")
    void reentrantListenerIsNotNested() {
        var holder = new AtomicReference<VeloxCache<String, Integer>>();
        var log = new ArrayList<String>();
        var reinserted = new AtomicInteger();
        var cache = cache(2, (k, v, c) -> {
            log.add("enter " + k);
            if (k.equals("A") && reinserted.getAndIncrement() == 0) {
                holder.get().put("D", 4);                            // triggers ANOTHER eviction, of B
            }
            log.add("exit " + k);
        });
        holder.set(cache);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.put("C", 3);                                           // evicts A; A's listener puts D, which evicts B

        assertEquals(List.of("enter A", "exit A", "enter B", "exit B"), log,
                "B's notification must wait for A's listener to return, not run inside it");
        assertTrue(cache.containsKey("C"));
        assertTrue(cache.containsKey("D"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a listener that throws does not fail the operation or lose other notifications")
    void throwingListenerIsContained() {
        var calls = new AtomicInteger();
        var cache = weighted(10, (k, v, c) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("listener bug");
        });
        cache.put("A", 2);
        cache.put("B", 2);
        cache.put("C", 2);

        cache.put("D", 9);                                           // evicts A, B and C

        assertEquals(3, calls.get(), "every notification is still attempted despite each one throwing");
        assertEquals(9, cache.getIfPresent("D"), "and the caller's put succeeded");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("an Error from a listener propagates, leaves the cache consistent, and keeps the rest queued")
    void errorFromListenerKeepsRemainingNotificationsQueued() {
        var events = new ArrayList<Event>();
        var failFirst = new AtomicInteger();
        var cache = weighted(10, (k, v, c) -> {
            if (failFirst.getAndIncrement() == 0) {
                throw new AssertionError("out of memory, say");
            }
            events.add(new Event(k, v, c));
        });
        cache.put("A", 2);
        cache.put("B", 2);
        cache.put("C", 2);

        assertThrows(AssertionError.class, () -> cache.put("D", 9));     // evicts A, B, C; A's listener fails

        cache.assertInvariants();
        // containsKey, not getIfPresent: a read would itself deliver the queued notifications.
        assertTrue(cache.containsKey("D"), "the update itself completed before delivery began");
        assertTrue(events.isEmpty(), "the two later notifications have not been delivered yet");

        cache.cleanUp();                                             // the next operation delivers them

        assertEquals(List.of(new Event("B", 2, RemovalCause.SIZE), new Event("C", 2, RemovalCause.SIZE)), events,
                "nothing was lost, and the dispatcher was not left stuck");
    }

    @Test
    @DisplayName("a cache with no listener still works and reports nothing anywhere")
    void noListener() {
        var cache = new VeloxCache<String, Integer>(2, new LruPolicy<>());

        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.invalidate("B");
        cache.invalidateAll();

        assertEquals(0, cache.size());
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  API contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a null value is rejected, and leaves the cache untouched")
    void nullValueIsRejected() {
        var cache = cache(5, (k, v, c) -> {
        });
        cache.put("A", 1);

        assertThrows(NullPointerException.class, () -> cache.put("B", null));

        assertEquals(1, cache.size());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("the builder wires the listener through")
    void builderWiresTheListener() {
        var events = new ArrayList<Event>();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(1)
                .removalListener((k, v, c) -> events.add(new Event(k, v, c)))
                .build();

        cache.put("A", 1);
        cache.put("B", 2);

        assertEquals(List.of(new Event("A", 1, RemovalCause.SIZE)), events);
        assertThrows(NullPointerException.class, () -> CacheBuilder.<String, Integer>newBuilder().removalListener(null));
    }

    // ------------------------------------------------------------------
    //  The conservation law
    // ------------------------------------------------------------------

    /** A value with an identity and a weight, so every put is a distinct thing to account for. */
    private static final class Val {
        final int id;
        final int weight;

        Val(int id, int weight) {
            this.id = id;
            this.weight = weight;
        }
    }

    /** An admission policy that turns every newcomer away once the cache is full. */
    private static final class RejectingPolicy<K, V> implements EvictionPolicy<K, V> {
        private final LruPolicy<K, V> delegate = new LruPolicy<>();

        @Override
        public void onInsert(Node<K, V> node) {
            delegate.onInsert(node);
        }

        @Override
        public void onAccess(Node<K, V> node) {
            delegate.onAccess(node);
        }

        @Override
        public void onRemove(Node<K, V> node) {
            delegate.onRemove(node);
        }

        @Override
        public Node<K, V> selectVictim() {
            return delegate.selectVictim();
        }

        @Override
        public boolean admit(Node<K, V> candidate, Node<K, V> victim) {
            return false;
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public String name() {
            return "REJECTING";
        }

        @Override
        public void assertInvariants(int expectedEntryCount) {
            delegate.assertInvariants(expectedEntryCount);
        }
    }

    /**
     * The law: after any sequence of operations, every value ever stored is EITHER
     * still resident OR was reported exactly once. Never both, never neither.
     *
     * <p>It cross-checks the listener against the cache's own statistics too: the
     * SIZE reports must equal evictions plus rejections, and the EXPIRED reports must
     * equal the expiration count.
     */
    private static void conserve(EvictionPolicy<String, Val> policy, long seed) {
        var clock = new FakeTicker();
        var reported = new int[200_000];
        var causeCounts = new long[RemovalCause.values().length];
        var cache = new VeloxCache<String, Val>(
                Capacity.<String, Val>weighted(40, (k, v) -> v.weight, 16), policy,
                new ExpiryConfig(20_000, -1, 0), clock, new HeapExpiryEngine<>(),
                (key, val, cause) -> {
                    reported[val.id]++;
                    causeCounts[cause.ordinal()]++;
                });
        var random = new Random(seed);
        var everStored = new ArrayList<Val>();
        int nextId = 0;

        for (int step = 0; step < 30_000; step++) {
            String key = "k" + random.nextInt(50);
            int action = random.nextInt(12);

            if (action <= 3 || action == 11) {
                // Mostly small entries; now and then one so heavy it cannot fit at all.
                int weight = random.nextInt(25) == 0 ? 30 + random.nextInt(31) : 1 + random.nextInt(9);
                var val = new Val(nextId++, weight);
                everStored.add(val);
                cache.put(key, val);
            } else if (action == 4) {
                var val = new Val(nextId++, 1 + random.nextInt(9));
                everStored.add(val);
                cache.put(key, val, Duration.ofNanos(1_000 + random.nextInt(30_000)));
            } else if (action == 5 || action == 6) {
                cache.getIfPresent(key);
            } else if (action == 7) {
                cache.invalidate(key);
            } else if (action == 8) {
                clock.advanceNanos(random.nextInt(8_000));
            } else if (action == 9) {
                cache.cleanUp();
            } else if (random.nextInt(200) == 0) {
                cache.invalidateAll();
            } else {
                cache.getIfPresent(key);
            }

            if (step % 500 == 0) {
                cache.assertInvariants();
            }
        }

        cache.cleanUp();
        cache.assertInvariants();

        Set<Integer> resident = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Val val = cache.getIfPresent("k" + i);
            if (val != null) {
                resident.add(val.id);
            }
        }

        for (Val val : everStored) {
            int accounted = reported[val.id] + (resident.contains(val.id) ? 1 : 0);
            assertEquals(1, accounted, "value " + val.id + " (" + policy.name() + "): reported "
                    + reported[val.id] + " time(s) and " + (resident.contains(val.id) ? "is" : "is not")
                    + " resident; it must be exactly one of those");
        }

        var stats = cache.stats();
        assertEquals(stats.evictionCount() + stats.rejectionCount(), causeCounts[RemovalCause.SIZE.ordinal()],
                policy.name() + ": SIZE reports must equal evictions plus rejections");
        assertEquals(stats.expirationCount(), causeCounts[RemovalCause.EXPIRED.ordinal()],
                policy.name() + ": EXPIRED reports must equal the expiration count");
        assertTrue(causeCounts[RemovalCause.REPLACED.ordinal()] > 0, "the run never replaced anything");
        assertTrue(causeCounts[RemovalCause.EXPLICIT.ordinal()] > 0, "the run never invalidated anything");
        assertTrue(causeCounts[RemovalCause.EXPIRED.ordinal()] > 0, "the run never expired anything");
        assertTrue(stats.rejectionCount() > 0, "the run never refused an oversized entry");
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    @DisplayName("conservation: every stored value is resident OR reported exactly once, under every policy")
    void everyValueIsAccountedFor(Policy policy) {
        EvictionPolicy<String, Val> instance = policy.create(16);
        conserve(instance, 900 + policy.ordinal());
    }

    @Test
    @DisplayName("conservation holds when an admission policy refuses newcomers too")
    void conservationWithAdmissionRefusals() {
        conserve(new RejectingPolicy<>(), 77);
    }

    @Test
    @DisplayName("a listener sees the values it is reported, unchanged by later operations")
    void reportedValuesAreTheOriginalObjects() {
        var seen = new ArrayList<Object>();
        var cache = new VeloxCache<String, Val>(Capacity.entries(1), new LruPolicy<>(), ExpiryConfig.NONE,
                Ticker.system(), new HeapExpiryEngine<>(), (k, v, c) -> seen.add(v));
        var first = new Val(1, 1);

        cache.put("A", first);
        cache.put("B", new Val(2, 1));

        assertEquals(1, seen.size());
        assertFalse(seen.get(0) == null);
        assertTrue(seen.get(0) == first, "the very object that was stored, not a copy");
    }
}
