package com.velox.core.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-thread tests for {@link SingleFlight}.
 *
 * <h2>How these stay deterministic</h2>
 *
 * Concurrency tests that rely on {@code Thread.sleep} either flake or hide bugs.
 * These never guess at timing. The loader is held on a latch until an
 * <i>observable condition</i> becomes true -- typically {@code coalesced() == N-1},
 * a counter that each follower increments BEFORE it blocks -- so by the time the
 * leader is released, every follower is provably already waiting.
 *
 * <p>A hard timeout on the whole class turns any deadlock, including one caused by
 * a future regression, into an ordinary test failure instead of a hung build.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SingleFlightTest {

    /** Spins (politely) until {@code condition} holds, failing rather than hanging. */
    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for: " + description);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for: " + description);
            }
        }
    }

    private static <T> List<Future<T>> submitAll(ExecutorService pool, int count, Callable<T> task) {
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(task));
        }
        return futures;
    }

    // ------------------------------------------------------------------
    //  Basics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a lone caller runs the loader once and leaves nothing behind")
    void loneCaller() {
        var flight = new SingleFlight<String, Integer>();

        int value = flight.load("A", key -> 7);

        assertEquals(7, value);
        assertEquals(1, flight.loads());
        assertEquals(0, flight.coalesced());
        assertEquals(0, flight.failures());
        assertEquals(0, flight.inFlightCount());
    }

    @Test
    @DisplayName("it is not a cache: a later call runs the loader again")
    void notAMemoisingCache() {
        var flight = new SingleFlight<String, Integer>();
        var runs = new AtomicInteger();

        int first = flight.load("A", key -> runs.incrementAndGet());
        int second = flight.load("A", key -> runs.incrementAndGet());

        assertEquals(1, first);
        assertEquals(2, second, "only OVERLAPPING calls share a load; a finished load is not remembered");
    }

    // ------------------------------------------------------------------
    //  The headline: the stampede
    // ------------------------------------------------------------------

    @Test
    @DisplayName("500 simultaneous requests for one key run the loader exactly once")
    void stampedeRunsOneLoad() throws Exception {
        final int callers = 500;
        var flight = new SingleFlight<String, Integer>();
        var loaderRuns = new AtomicInteger();
        var release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);

        try {
            List<Future<Integer>> results = submitAll(pool, callers, () -> flight.load("hot", key -> {
                loaderRuns.incrementAndGet();
                try {
                    release.await();                   // the "slow database query"
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return 42;
            }));

            // Hold the leader until every one of the other 499 is provably waiting.
            awaitCondition(() -> flight.coalesced() == callers - 1, "all followers to be waiting");
            assertEquals(1, loaderRuns.get(), "while everyone waits, exactly one loader is running");
            assertEquals(1, flight.inFlightCount());
            release.countDown();

            for (Future<Integer> result : results) {
                assertEquals(42, result.get(20, TimeUnit.SECONDS), "every caller must get the shared result");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, loaderRuns.get(), "500 callers, ONE database query");
        assertEquals(1, flight.loads());
        assertEquals(callers - 1, flight.coalesced());
        assertEquals(0, flight.inFlightCount());
        System.out.printf("  [stampede] %d concurrent callers -> %d load(s), %d coalesced%n",
                callers, flight.loads(), flight.coalesced());
    }

    @Test
    @DisplayName("different keys load in parallel: coalescing is per key, not global")
    void differentKeysDoNotSerialise() throws Exception {
        var flight = new SingleFlight<String, Integer>();
        // Both loaders must be running AT THE SAME TIME to pass the barrier. If the
        // component serialised unrelated keys, the second loader could never start
        // while the first waits, the barrier would time out, and this would fail.
        var bothRunning = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> a = pool.submit(() -> flight.load("A", key -> await(bothRunning, 1)));
            Future<Integer> b = pool.submit(() -> flight.load("B", key -> await(bothRunning, 2)));

            assertEquals(1, a.get(20, TimeUnit.SECONDS));
            assertEquals(2, b.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, flight.loads());
        assertEquals(0, flight.coalesced());
    }

    private static int await(CyclicBarrier barrier, int result) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("the two loads never overlapped", e);
        }
    }

    // ------------------------------------------------------------------
    //  Failures
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a failing load fails every waiting caller with the same exception")
    void failureIsSharedThenForgotten() throws Exception {
        final int callers = 30;
        var flight = new SingleFlight<String, Integer>();
        var release = new CountDownLatch(1);
        var boom = new IllegalStateException("database is down");
        ExecutorService pool = Executors.newFixedThreadPool(callers);

        try {
            List<Future<Integer>> results = submitAll(pool, callers, () -> flight.load("K", key -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                throw boom;
            }));
            awaitCondition(() -> flight.coalesced() == callers - 1, "all followers to be waiting");
            release.countDown();

            for (Future<Integer> result : results) {
                var thrown = assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
                assertSame(boom, thrown.getCause(), "everyone sees the very exception the leader threw");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, flight.loads());
        assertEquals(1, flight.failures());
        assertEquals(0, flight.inFlightCount(), "a failed load must not linger in the map");

        // The failure was shared but NOT remembered: the next caller starts afresh.
        assertEquals(99, flight.load("K", key -> 99));
        assertEquals(2, flight.loads());
    }

    @Test
    @DisplayName("an Error from the loader reaches followers as the same Error")
    void errorsPropagate() throws Exception {
        var flight = new SingleFlight<String, Integer>();
        var release = new CountDownLatch(1);
        var error = new AssertionError("disk full");
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> leader = pool.submit(() -> flight.load("K", key -> {
                awaitLatch(release);
                throw error;
            }));
            awaitCondition(() -> flight.loads() == 1, "the leader to start");
            Future<Integer> follower = pool.submit(() -> flight.load("K", key -> 0));
            awaitCondition(() -> flight.coalesced() == 1, "the follower to be waiting");
            release.countDown();

            assertSame(error, assertThrows(ExecutionException.class, () -> leader.get(20, TimeUnit.SECONDS)).getCause());
            assertSame(error, assertThrows(ExecutionException.class, () -> follower.get(20, TimeUnit.SECONDS)).getCause());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, flight.inFlightCount());
    }

    @Test
    @DisplayName("a checked exception smuggled through a Function is wrapped for leader and follower alike")
    void sneakyCheckedExceptionsAreWrapped() throws Exception {
        var flight = new SingleFlight<String, Integer>();
        var release = new CountDownLatch(1);
        var checked = new java.io.IOException("connection reset");
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> leader = pool.submit(() -> flight.load("K", key -> {
                awaitLatch(release);
                return SingleFlightTest.<Integer, RuntimeException>sneakyThrow(checked);
            }));
            awaitCondition(() -> flight.loads() == 1, "the leader to start");
            Future<Integer> follower = pool.submit(() -> flight.load("K", key -> 0));
            awaitCondition(() -> flight.coalesced() == 1, "the follower to be waiting");
            release.countDown();

            var leaderFailure = assertThrows(ExecutionException.class, () -> leader.get(20, TimeUnit.SECONDS));
            var followerFailure = assertThrows(ExecutionException.class, () -> follower.get(20, TimeUnit.SECONDS));
            assertInstanceOf(CacheLoadException.class, leaderFailure.getCause());
            assertSame(checked, leaderFailure.getCause().getCause());
            assertInstanceOf(CacheLoadException.class, followerFailure.getCause());
            assertSame(checked, followerFailure.getCause().getCause());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, flight.inFlightCount());
    }

    @SuppressWarnings("unchecked")
    private static <R, T extends Throwable> R sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a null result is shared like any other")
    void nullResultIsShared() throws Exception {
        var flight = new SingleFlight<String, Integer>();
        var release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> leader = pool.submit(() -> flight.load("K", key -> {
                awaitLatch(release);
                return null;
            }));
            awaitCondition(() -> flight.loads() == 1, "the leader to start");
            Future<Integer> follower = pool.submit(() -> flight.load("K", key -> 5));
            awaitCondition(() -> flight.coalesced() == 1, "the follower to be waiting");
            release.countDown();

            assertNull(leader.get(20, TimeUnit.SECONDS));
            assertNull(follower.get(20, TimeUnit.SECONDS), "the follower must receive the leader's null, not run its own loader");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("failed loads never leave entries behind")
    void repeatedFailuresLeaveNoGarbage() {
        var flight = new SingleFlight<String, Integer>();

        for (int i = 0; i < 1_000; i++) {
            assertThrows(IllegalStateException.class, () -> flight.load("K" + (int) (Math.random() * 10), key -> {
                throw new IllegalStateException("nope");
            }));
        }

        assertEquals(0, flight.inFlightCount());
        assertEquals(1_000, flight.failures());
    }

    // ------------------------------------------------------------------
    //  Recursion and interruption
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)   // if detection breaks, this deadlocks: fail fast
    @DisplayName("a loader asking for its own key fails loudly instead of deadlocking")
    void directRecursionIsDetected() {
        var flight = new SingleFlight<String, Integer>();

        var thrown = assertThrows(IllegalStateException.class,
                () -> flight.load("A", key -> flight.load("A", k -> 1)));

        assertTrue(thrown.getMessage().contains("recursive"), thrown.getMessage());
        assertEquals(0, flight.inFlightCount(), "the failed attempt must clean up after itself");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("a cycle A -> B -> A on one thread is detected too")
    void indirectRecursionIsDetected() {
        var flight = new SingleFlight<String, Integer>();

        assertThrows(IllegalStateException.class, () ->
                flight.load("A", a -> flight.load("B", b -> flight.load("A", again -> 1))));

        assertEquals(0, flight.inFlightCount());
    }

    @Test
    @DisplayName("a loader may legitimately load a DIFFERENT key")
    void nestedLoadsOfDifferentKeysWork() {
        var flight = new SingleFlight<String, Integer>();

        int value = flight.load("A", a -> 1 + flight.load("B", b -> 10));

        assertEquals(11, value);
        assertEquals(0, flight.inFlightCount());
    }

    @Test
    @DisplayName("an interrupted follower reports it, keeps its interrupt flag, and does not disturb the leader")
    void interruptedFollower() throws Exception {
        var flight = new SingleFlight<String, Integer>();
        var release = new CountDownLatch(1);
        var followerFailure = new AtomicReference<Throwable>();
        var flagStillSet = new AtomicBoolean();

        Thread leader = new Thread(() -> flight.load("K", key -> {
            awaitLatch(release);
            return 5;
        }));
        leader.start();
        awaitCondition(() -> flight.loads() == 1, "the leader to start");

        Thread follower = new Thread(() -> {
            try {
                flight.load("K", key -> 0);
            } catch (Throwable t) {
                followerFailure.set(t);
                flagStillSet.set(Thread.currentThread().isInterrupted());
            }
        });
        follower.start();
        awaitCondition(() -> flight.coalesced() == 1, "the follower to be waiting");

        follower.interrupt();
        follower.join(10_000);

        assertInstanceOf(CacheLoadException.class, followerFailure.get());
        assertInstanceOf(InterruptedException.class, followerFailure.get().getCause());
        assertTrue(flagStillSet.get(), "swallowing an interrupt would hide it from the code that asked for it");

        release.countDown();
        leader.join(10_000);
        assertEquals(0, flight.inFlightCount());
        assertEquals(1, flight.loads(), "the leader carried on unaffected");
    }

    // ------------------------------------------------------------------
    //  The ordering rule: remove the entry BEFORE releasing followers
    // ------------------------------------------------------------------

    /**
     * A key whose {@code hashCode()} can be made to park the calling thread.
     *
     * <p>{@code ConcurrentHashMap.remove(key, value)} calls {@code hashCode()}, so a
     * thread inside that call is a thread paused mid-removal. That gives the test a
     * deterministic place to stop the leader, which is otherwise impossible: the gap
     * between "remove the entry" and "release the followers" is a few nanoseconds.
     */
    private static final class GatedKey {
        private final String name;
        private volatile boolean armed;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        GatedKey(String name) {
            this.name = name;
        }

        void arm() {
            armed = true;
        }

        @Override
        public int hashCode() {
            if (armed) {
                entered.countDown();
                awaitLatch(proceed);
            }
            return name.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GatedKey g && g.name.equals(name);
        }
    }

    @Test
    @DisplayName("followers are released only AFTER the finished call has been removed from the map")
    void entryIsRemovedBeforeFollowersAreReleased() throws Exception {
        // Why the order matters: if followers were released first, one of them could
        // (and a caller arriving in that gap could) find the finished call still in the
        // map and inherit its outcome. After a FAILURE that means receiving an error that
        // was already old news, instead of retrying.
        var flight = new SingleFlight<GatedKey, Integer>();
        var key = new GatedKey("K");
        var release = new CountDownLatch(1);
        var followerAwake = new AtomicBoolean();
        var inFlightWhenFollowerWoke = new AtomicInteger(-1);

        Thread leader = new Thread(() -> flight.load(key, k -> {
            awaitLatch(release);
            return 1;
        }));
        leader.start();
        awaitCondition(() -> flight.loads() == 1, "the leader to start");

        Thread follower = new Thread(() -> {
            flight.load(key, k -> 0);
            inFlightWhenFollowerWoke.set(flight.inFlightCount());
            followerAwake.set(true);
        });
        follower.start();
        awaitCondition(() -> flight.coalesced() == 1, "the follower to be waiting");

        key.arm();                       // from now on, hashCode() parks whoever calls it
        release.countDown();             // the loader returns; the leader heads for the map removal

        assertTrue(key.entered.await(10, TimeUnit.SECONDS), "the leader never reached the removal");

        // The leader is now parked INSIDE the removal. The follower must still be asleep:
        // if it has woken, it was released before the entry was removed. Give a wrongly-
        // ordered implementation ample time to show itself (it needs microseconds).
        long watchUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        while (System.nanoTime() < watchUntil) {
            if (followerAwake.get()) {
                fail("a follower was released while the finished call was still in the map");
            }
            Thread.sleep(2);
        }

        key.proceed.countDown();         // let the leader finish its removal and release everyone
        leader.join(10_000);
        follower.join(10_000);

        assertTrue(followerAwake.get(), "the follower must be released once the leader finishes");
        assertEquals(0, inFlightWhenFollowerWoke.get(),
                "by the time a follower wakes, the entry must already be gone");
    }

    // ------------------------------------------------------------------
    //  Stress: the invariant that matters
    // ------------------------------------------------------------------

    @Test
    @DisplayName("under heavy contention no key ever has two loaders running at once")
    void mutualExclusionPerKeyUnderStress() throws Exception {
        final int threads = 16;
        final int callsPerThread = 5_000;
        final int keys = 8;

        var flight = new SingleFlight<Integer, Integer>();
        var activeLoaders = new AtomicIntegerArray(keys);
        var violations = new AtomicInteger();
        var wrongResults = new AtomicInteger();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                workers.add(pool.submit(() -> {
                    awaitLatch(start);
                    var random = new java.util.SplittableRandom(seed);
                    for (int i = 0; i < callsPerThread; i++) {
                        int key = random.nextInt(keys);
                        int value = flight.load(key, k -> {
                            // If two loaders for the same key ever overlapped, this counter would exceed 1.
                            if (activeLoaders.incrementAndGet(k) > 1) {
                                violations.incrementAndGet();
                            }
                            Thread.yield();              // widen the window for a race to show itself
                            activeLoaders.decrementAndGet(k);
                            return k * 1000;
                        });
                        if (value != key * 1000) {
                            wrongResults.incrementAndGet();
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(50, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, violations.get(), "two loaders overlapped for the same key");
        assertEquals(0, wrongResults.get(), "a caller received the wrong key's value");
        assertEquals(0, flight.inFlightCount());
        assertEquals((long) threads * callsPerThread, flight.loads() + flight.coalesced(),
                "every call must have either led a load or followed one");
        System.out.printf("  [stress] %d calls: %d loads ran, %d calls were coalesced%n",
                threads * callsPerThread, flight.loads(), flight.coalesced());
    }
}
