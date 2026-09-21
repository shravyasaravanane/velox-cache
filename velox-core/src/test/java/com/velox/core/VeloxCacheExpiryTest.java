package com.velox.core;

import com.velox.core.expiry.ExpiryConfig;
import com.velox.core.expiry.ExpiryEngine;
import com.velox.core.expiry.FakeTicker;
import com.velox.core.policy.LruPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expiry behaviour, checked against a fake clock so every boundary is exact.
 *
 * <p>This class is abstract: it states what ANY expiry engine must do, and each
 * concrete subclass supplies one engine. Every test here therefore runs once
 * per engine, so the indexed heap and the timing wheel are held to exactly the
 * same behaviour and any divergence between them is a failure.
 */
abstract class VeloxCacheExpiryTest {

    private static final Duration S = Duration.ofSeconds(1);

    /**
     * @return a fresh engine to test. The fake clock starts at 0, so a wheel is
     *         built with a start time of 0 (reading the ticker instead would
     *         count as a clock read and break the "no clock reads" test).
     */
    protected abstract ExpiryEngine<String, Integer> newEngine();

    protected VeloxCache<String, Integer> cache(
            int capacity, FakeTicker ticker, long writeSeconds, long accessSeconds, double jitter) {
        var config = new ExpiryConfig(
                writeSeconds < 0 ? -1 : S.toNanos() * writeSeconds,
                accessSeconds < 0 ? -1 : S.toNanos() * accessSeconds,
                jitter);
        return new VeloxCache<>(capacity, new LruPolicy<>(), config, ticker, newEngine());
    }

    // ------------------------------------------------------------------
    //  Expire after write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an entry is alive one nanosecond before its deadline and dead exactly at it")
    void exactBoundary() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, 10, -1, 0);
        cache.put("A", 1);

        clock.advance(Duration.ofSeconds(10)).advanceNanos(-1);
        assertEquals(1, cache.getIfPresent("A"), "1ns before the deadline the entry must still be served");

        clock.advanceNanos(1);
        assertNull(cache.getIfPresent("A"), "at the deadline the entry is expired");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("an expired read is a miss and an expiration, never a hit")
    void expiredReadCountsAsMiss() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, 5, -1, 0);
        cache.put("A", 1);
        clock.advance(Duration.ofSeconds(6));

        assertNull(cache.getIfPresent("A"));

        var stats = cache.stats();
        assertEquals(0, stats.hitCount(), "expired data must never count as a hit");
        assertEquals(1, stats.missCount());
        assertEquals(1, stats.expirationCount());
        assertEquals(0, stats.evictionCount(), "timing out is not memory pressure, so it is not an eviction");
        assertEquals(0, cache.size(), "the dead entry must have been removed");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("reading an entry does not extend an expire-after-write deadline")
    void readsDoNotExtendWriteTtl() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, 10, -1, 0);
        cache.put("A", 1);

        for (int i = 0; i < 9; i++) {
            clock.advance(S);
            assertEquals(1, cache.getIfPresent("A"));    // a read every second
        }
        clock.advance(S);                                // t = 10s

        assertNull(cache.getIfPresent("A"), "however popular, a write TTL still bounds staleness");
    }

    @Test
    @DisplayName("overwriting an entry restarts its write TTL")
    void overwriteRestartsTtl() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, 10, -1, 0);
        cache.put("A", 1);
        clock.advance(Duration.ofSeconds(8));

        cache.put("A", 2);                               // fresh value, fresh clock
        clock.advance(Duration.ofSeconds(8));            // t = 16s: past the ORIGINAL deadline

        assertEquals(2, cache.getIfPresent("A"), "the rewrite reset the clock, so it lives until t=18s");
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Expire after access
    // ------------------------------------------------------------------

    @Test
    @DisplayName("each read restarts the idle clock")
    void readsExtendAccessTtl() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, -1, 5, 0);
        cache.put("A", 1);

        for (int i = 0; i < 20; i++) {
            clock.advance(Duration.ofSeconds(4));        // always inside the 5s idle window
            assertEquals(1, cache.getIfPresent("A"), "still being used, so still alive at round " + i);
        }
        cache.assertInvariants();
    }

    @Test
    @DisplayName("an entry left idle past the timeout expires")
    void idleEntryExpires() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, -1, 5, 0);
        cache.put("A", 1);

        clock.advance(Duration.ofSeconds(5));

        assertNull(cache.getIfPresent("A"));
        assertEquals(1, cache.stats().expirationCount());
    }

    @Test
    @DisplayName("with both TTLs, reads keep an entry alive but never past the write deadline")
    void writeDeadlineCapsAccessExtension() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, 10, 4, 0);
        cache.put("A", 1);

        for (int t = 3; t <= 9; t += 3) {                // reads at t = 3, 6, 9
            clock.advance(Duration.ofSeconds(3));
            assertEquals(1, cache.getIfPresent("A"), "alive at t=" + t);
        }
        // The last read at t=9 would extend the idle deadline to t=13, but the
        // write deadline is t=10 and the entry expires at the EARLIER of the two.
        clock.advance(Duration.ofSeconds(1));            // t = 10s

        assertNull(cache.getIfPresent("A"), "the hard write deadline must win");
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Per-entry TTL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an explicit TTL works even with no expiry configured")
    void explicitTtlWithoutConfig() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, -1, -1, 0);

        cache.put("short", 1, Duration.ofSeconds(3));
        cache.put("forever", 2);
        clock.advance(Duration.ofSeconds(4));

        assertNull(cache.getIfPresent("short"));
        assertEquals(2, cache.getIfPresent("forever"), "an entry with no TTL must never expire");
        cache.assertInvariants();
    }

    @Test
    @DisplayName("an explicit TTL overrides the configured one")
    void explicitOverridesConfigured() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, 10, -1, 0);

        cache.put("A", 1, Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(3));

        assertNull(cache.getIfPresent("A"), "the 2s explicit TTL beats the 10s default");
    }

    @Test
    @DisplayName("a later plain put replaces an explicit TTL with the configured one")
    void plainPutReplacesExplicitTtl() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, -1, -1, 0);
        cache.put("A", 1, Duration.ofSeconds(2));

        cache.put("A", 2);                               // no TTL configured -> never expires
        clock.advance(Duration.ofSeconds(100));

        assertEquals(2, cache.getIfPresent("A"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("a non-positive TTL is rejected")
    void rejectsInvalidTtl() {
        var cache = cache(10, new FakeTicker(), -1, -1, 0);

        assertThrows(IllegalArgumentException.class, () -> cache.put("A", 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> cache.put("A", 1, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> cache.put("A", 1, null));
    }

    // ------------------------------------------------------------------
    //  Sweeping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a put removes dead entries before evicting live ones")
    void putSweepsBeforeEvicting() {
        // The reason for sweeping on write. Without it, "A" -- dead, but the
        // MOST recently used -- would sit in the cache while LRU evicted a
        // perfectly live entry to make room.
        var clock = new FakeTicker();
        var cache = cache(3, clock, -1, -1, 0);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.put("A", 1, Duration.ofSeconds(5));        // newest, and short-lived
        clock.advance(Duration.ofSeconds(6));            // A is now dead

        cache.put("D", 4);                               // full? A must be reaped, not B

        assertNull(cache.getIfPresent("A"));
        assertEquals(2, cache.getIfPresent("B"), "B is live and must survive");
        assertEquals(3, cache.getIfPresent("C"));
        assertEquals(4, cache.getIfPresent("D"));
        assertEquals(0, cache.stats().evictionCount(), "nothing live should have been evicted");
        assertEquals(1, cache.stats().expirationCount());
        cache.assertInvariants();
    }

    @Test
    @DisplayName("containsKey reports an expired entry as absent without removing it")
    void containsKeyDoesNotMutate() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, 5, -1, 0);
        cache.put("A", 1);
        clock.advance(Duration.ofSeconds(6));

        assertFalse(cache.containsKey("A"));

        assertEquals(1, cache.size(), "a query must not change the cache");
        assertEquals(0, cache.stats().expirationCount());
    }

    @Test
    @DisplayName("cleanUp removes every expired entry at once")
    void cleanUpDrainsEverything() {
        var clock = new FakeTicker();
        var cache = cache(100, clock, 5, -1, 0);
        for (int i = 0; i < 50; i++) {
            cache.put("k" + i, i);
        }
        clock.advance(Duration.ofSeconds(6));
        assertEquals(50, cache.size(), "nothing has swept yet, so the dead entries still occupy memory");

        cache.cleanUp();

        assertEquals(0, cache.size());
        assertEquals(50, cache.stats().expirationCount());
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  The three structures stay in step
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalidate, eviction and invalidateAll all cancel their expiry")
    void removalsCancelExpiry() {
        var clock = new FakeTicker();
        var cache = cache(3, clock, 100, -1, 0);

        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.invalidate("A");
        cache.assertInvariants();          // would throw if A were still scheduled

        cache.put("D", 4);
        cache.put("E", 5);                 // evicts B
        cache.assertInvariants();

        cache.invalidateAll();
        cache.assertInvariants();
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("a cache with no TTLs never reads the clock")
    void noClockReadsWithoutExpiry() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, -1, -1, 0);

        for (int i = 0; i < 100; i++) {
            cache.put("k" + i, i);
            cache.getIfPresent("k" + i);
        }

        assertEquals(0, clock.reads(),
                "reading the clock costs tens of nanoseconds; a cache that never expires should not pay it");
    }

    // ------------------------------------------------------------------
    //  Jitter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("without jitter every entry expires in the same instant")
    void noJitterMeansACliff() {
        var clock = new FakeTicker();
        var cache = cache(2000, clock, 100, -1, 0);
        for (int i = 0; i < 1000; i++) {
            cache.put("k" + i, i);
        }

        clock.advance(Duration.ofSeconds(100)).advanceNanos(-1);
        cache.cleanUp();
        assertEquals(1000, cache.size(), "1ns early: nothing has expired");

        clock.advanceNanos(1);
        cache.cleanUp();
        assertEquals(0, cache.size(), "in one instant, all 1000 are gone -- a stampede on the database");
    }

    @Test
    @DisplayName("jitter spreads expiry across a window instead of a single instant")
    void jitterSpreadsExpiry() {
        var clock = new FakeTicker();
        var cache = cache(2000, clock, 100, -1, 0.15);
        for (int i = 0; i < 1000; i++) {
            cache.put("k" + i, i);
        }

        // TTLs are 100s +/- 15%, i.e. somewhere in [85s, 115s).
        clock.advance(Duration.ofSeconds(84));
        cache.cleanUp();
        assertEquals(1000, cache.size(), "before the earliest possible deadline nothing expires");

        clock.advance(Duration.ofSeconds(16));           // t = 100s, the middle of the window
        cache.cleanUp();
        int alive = cache.size();
        System.out.printf("  [jitter] at the nominal TTL, %d of 1000 entries remain (no jitter: 0)%n", alive);
        assertTrue(alive > 350 && alive < 650,
                "about half should have expired by the nominal TTL; " + alive + " remain");

        clock.advance(Duration.ofSeconds(16));           // t = 116s, past the latest deadline
        cache.cleanUp();
        assertEquals(0, cache.size(), "everything expires by the end of the window");
        cache.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Differential test against a naive time model
    // ------------------------------------------------------------------

    /**
     * An obviously-correct model of expiry: a map of (value, deadlines), with
     * liveness checked by comparing against the clock on every query. No heap,
     * no sweeping -- just the definition.
     */
    private static final class NaiveTtlCache {
        private static final class Entry {
            int value;
            boolean hasHard;
            long hard;
            boolean hasEffective;
            long effective;
        }

        private final Map<String, Entry> map = new HashMap<>();
        private final long writeTtl;
        private final long accessTtl;

        NaiveTtlCache(long writeTtl, long accessTtl) {
            this.writeTtl = writeTtl;
            this.accessTtl = accessTtl;
        }

        private boolean alive(Entry e, long now) {
            return !e.hasEffective || now < e.effective;
        }

        private void recompute(Entry e, long now) {
            boolean has = e.hasHard;
            long deadline = e.hard;
            if (accessTtl >= 0) {
                long idle = now + accessTtl;
                if (!has || idle < deadline) {
                    deadline = idle;
                }
                has = true;
            }
            e.hasEffective = has;
            e.effective = deadline;
        }

        void put(String key, int value, long explicitTtl, long now) {
            Entry e = new Entry();
            e.value = value;
            long ttl = explicitTtl >= 0 ? explicitTtl : writeTtl;
            if (ttl >= 0) {
                e.hasHard = true;
                e.hard = now + ttl;
            }
            recompute(e, now);
            map.put(key, e);
        }

        Integer get(String key, long now) {
            Entry e = map.get(key);
            if (e == null) {
                return null;
            }
            if (!alive(e, now)) {
                map.remove(key);
                return null;
            }
            if (accessTtl >= 0) {
                recompute(e, now);
            }
            return e.value;
        }

        boolean contains(String key, long now) {
            Entry e = map.get(key);
            return e != null && alive(e, now);
        }

        void invalidate(String key) {
            map.remove(key);
        }

        long aliveCount(long now) {
            return map.values().stream().filter(e -> alive(e, now)).count();
        }
    }

    protected void differential(long writeTicks, long accessTicks, long seed) {
        final long tick = 1_000;                       // nanoseconds per model "tick"
        var clock = new FakeTicker();
        var real = new VeloxCache<String, Integer>(
                10_000, new LruPolicy<>(),
                new ExpiryConfig(writeTicks < 0 ? -1 : writeTicks * tick,
                        accessTicks < 0 ? -1 : accessTicks * tick, 0),
                clock, newEngine());
        var naive = new NaiveTtlCache(writeTicks < 0 ? -1 : writeTicks * tick,
                accessTicks < 0 ? -1 : accessTicks * tick);
        var random = new Random(seed);
        long now = 0;

        for (int step = 0; step < 60_000; step++) {
            String key = "k" + random.nextInt(60);
            int action = random.nextInt(12);

            if (action < 4) {
                int value = random.nextInt(1_000_000);
                boolean explicit = random.nextInt(10) < 3;
                long ttlTicks = 1 + random.nextInt(80);
                if (explicit) {
                    real.put(key, value, Duration.ofNanos(ttlTicks * tick));
                    naive.put(key, value, ttlTicks * tick, now);
                } else {
                    real.put(key, value);
                    naive.put(key, value, -1, now);
                }
            } else if (action < 8) {
                assertEquals(naive.get(key, now), real.getIfPresent(key),
                        "step " + step + " disagreed on get(" + key + ") at t=" + now);
            } else if (action == 8) {
                assertEquals(naive.contains(key, now), real.containsKey(key),
                        "step " + step + " disagreed on containsKey(" + key + ")");
            } else if (action == 9) {
                real.invalidate(key);
                naive.invalidate(key);
            } else if (action == 10) {
                long jump = random.nextInt(16) * tick;
                clock.advanceNanos(jump);
                now += jump;
            } else {
                real.cleanUp();
                assertEquals(naive.aliveCount(now), real.size(),
                        "step " + step + ": after cleanUp the sizes must match the live count");
            }

            if (step % 200 == 0) {
                real.assertInvariants();
            }
        }
        real.assertInvariants();
    }

    @Test
    @DisplayName("matches a naive time model: expire-after-write only")
    void differentialWriteOnly() {
        differential(50, -1, 1);
    }

    @Test
    @DisplayName("matches a naive time model: expire-after-access only")
    void differentialAccessOnly() {
        differential(-1, 30, 2);
    }

    @Test
    @DisplayName("matches a naive time model: both TTLs together")
    void differentialBoth() {
        differential(50, 30, 3);
    }

    @Test
    @DisplayName("matches a naive time model: no configured TTL, explicit TTLs only")
    void differentialExplicitOnly() {
        differential(-1, -1, 4);
    }

    @Test
    @DisplayName("an entry whose deadline moved later must not hide an earlier deadline behind it")
    void rescheduledEntryDoesNotHideDueEntries() {
        // The classic indexed-heap bug. A is the earliest deadline, so it sits at
        // the heap's root. If its deadline is pushed later but the heap is not
        // repositioned, A stays at the root -- and the sweep, which only ever
        // looks at the root, sees "not due yet" and stops. B, whose deadline
        // really HAS passed, is stuck behind it and never removed.
        var clock = new FakeTicker();
        var cache = cache(10, clock, -1, -1, 0);
        cache.put("A", 1, Duration.ofSeconds(5));         // deadline t=5  (root)
        cache.put("B", 2, Duration.ofSeconds(8));         // deadline t=8

        clock.advance(Duration.ofSeconds(1));
        cache.put("A", 3, Duration.ofSeconds(20));        // A moves to t=21, so B is now earliest

        clock.advance(Duration.ofSeconds(8));             // t=9: B is due, A is not
        cache.cleanUp();

        assertEquals(1, cache.size(), "B (t=8) has expired and must have been swept");
        assertEquals(3, cache.getIfPresent("A"));
        assertNull(cache.getIfPresent("B"));
        cache.assertInvariants();
    }

    @Test
    @DisplayName("stays valid when an entry that just expired is overwritten")
    void overwriteAnExpiredEntry() {
        var clock = new FakeTicker();
        var cache = cache(10, clock, 5, -1, 0);
        cache.put("A", 1);
        clock.advance(Duration.ofSeconds(9));

        cache.put("A", 2);                               // A is dead but not yet swept by anyone else

        assertEquals(2, cache.getIfPresent("A"));
        assertNotNull(cache.stats());
        cache.assertInvariants();
    }
}
