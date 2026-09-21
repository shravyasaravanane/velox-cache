package com.velox.core.loader;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Makes concurrent requests for the same key share ONE load.
 *
 * <h2>The problem: the stampede</h2>
 *
 * A hot key expires at 12:00:00.000. At that instant 1,000 requests for it are
 * in flight. All 1,000 check the cache, all 1,000 miss, and all 1,000 go to the
 * database for the identical row at the identical moment. The cache has just
 * <i>amplified</i> the load it was built to absorb, at the worst possible time
 * -- precisely when the database is busiest -- and this is one of the most
 * common ways a caching layer takes down the system behind it.
 *
 * <p>Only <b>one</b> of those 1,000 requests needs to reach the database. The
 * other 999 should wait for that answer and share it.
 *
 * <h2>How it works: a leader and its followers</h2>
 *
 * <pre>
 *   thread 1 --&gt; load("K") --&gt; nobody is loading K --&gt; LEADER: runs the loader
 *   thread 2 --&gt; load("K") --&gt; K is in flight       --&gt; FOLLOWER: waits
 *   thread 3 --&gt; load("K") --&gt; K is in flight       --&gt; FOLLOWER: waits
 *                                                    ...
 *   leader finishes --&gt; every follower receives the SAME result
 * </pre>
 *
 * The map of in-flight loads is a {@link ConcurrentHashMap}, and the moment of
 * becoming leader is a single atomic {@code putIfAbsent}: of any number of
 * threads racing for the same key, exactly one wins.
 *
 * <h2>Design decisions</h2>
 *
 * <ul>
 *   <li><b>The leader's own thread runs the loader.</b> No thread pool is
 *       involved. That keeps the component dependency-free and gives natural
 *       backpressure: the thread that wants the data is the one that pays for it.</li>
 *
 *   <li><b>Failures are shared but never cached.</b> If the loader throws, every
 *       waiting caller receives that same failure, because they all asked for
 *       the same thing and it did not work. But the in-flight entry is then
 *       removed, so the <i>next</i> caller retries fresh instead of inheriting a
 *       stale error.</li>
 *
 *   <li><b>The in-flight entry is removed BEFORE followers are released.</b> If
 *       it were removed after, a caller arriving in the gap would find the
 *       already-finished call and inherit its outcome; after a failure that
 *       means receiving an error that was old news the moment it arrived.</li>
 *
 *   <li><b>Recursion is detected, not deadlocked.</b> A loader that asks for
 *       its own key would wait on itself forever. The leader's thread is
 *       recorded, and a same-thread re-entry throws instead of hanging.</li>
 * </ul>
 *
 * <h2>Known limits</h2>
 *
 * <ul>
 *   <li><b>A narrow duplicate-load window remains.</b> A caller can miss the
 *       cache, then have the leader finish and clear its entry, then become a
 *       new leader itself and load again. That costs at most one redundant load
 *       per completed flight, not a stampede, and the second load returns the same
 *       value. (Go's {@code singleflight} has the same property.)</li>
 *   <li><b>Cyclic dependencies across threads can deadlock.</b> If thread 1 leads
 *       key A and its loader needs B while thread 2 leads B and its loader needs
 *       A, each waits on the other. Same-thread cycles are detected; cross-thread
 *       ones cannot be, cheaply. Loaders should not depend on each other cyclically.</li>
 * </ul>
 *
 * <h2>Counters</h2>
 *
 * Three thread-safe counters ({@link LongAdder}, which stripes its cells so
 * many threads can increment without contending on one cache line) record how
 * many loads ran, how many failed, and how many callers were spared a load.
 * The last is the headline number: <b>the database queries a stampede would have
 * caused, and did not.</b>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class SingleFlight<K, V> {

    /** One load in progress: who is running it, and where followers wait for the outcome. */
    private static final class Call<V> {
        final Thread leader;
        final CountDownLatch done = new CountDownLatch(1);

        // Written once by the leader BEFORE done.countDown(), read by followers AFTER
        // done.await() returns. The latch gives the happens-before edge, so these
        // plain fields are safely published without being volatile.
        V value;
        Throwable failure;

        Call(Thread leader) {
            this.leader = leader;
        }
    }

    private final ConcurrentHashMap<K, Call<V>> inFlight = new ConcurrentHashMap<>();
    private final LongAdder loads = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder coalesced = new LongAdder();

    /**
     * Loads the value for {@code key}, or waits for a load already under way.
     *
     * @param key    the key to load
     * @param loader computes the value; runs on the calling thread, only if this
     *               caller becomes the leader. May return {@code null}
     * @return the loaded value, shared by every caller that overlapped this load
     * @throws IllegalStateException if the loader for {@code key} re-entered
     *                               {@code load} for the same key on the same thread
     * @throws CacheLoadException    if this thread was interrupted while waiting for
     *                               another thread's load
     * @throws RuntimeException      whatever the loader threw, to leader and followers alike
     */
    public V load(K key, Function<? super K, ? extends V> loader) {
        Call<V> mine = new Call<>(Thread.currentThread());
        Call<V> existing = inFlight.putIfAbsent(key, mine);     // the atomic election

        if (existing != null) {
            return follow(key, existing);
        }

        // We won: we are the leader.
        loads.increment();
        try {
            V value = loader.apply(key);
            mine.value = value;
            return value;
        } catch (RuntimeException | Error e) {
            failures.increment();
            mine.failure = e;
            throw e;
        } catch (Throwable other) {
            // Only reachable if a "sneaky" checked exception escapes a Function.
            failures.increment();
            mine.failure = other;
            throw new CacheLoadException("load failed for key " + key, other);
        } finally {
            // ORDER MATTERS: first take the call out of the map, THEN release the
            // followers. See the class comment. The followers already hold `mine`,
            // so they still receive the result.
            inFlight.remove(key, mine);
            mine.done.countDown();
        }
    }

    private V follow(K key, Call<V> call) {
        if (call.leader == Thread.currentThread()) {
            // Waiting would block this thread on a load that only this same thread can
            // complete, and it is busy waiting: a guaranteed deadlock. Fail loudly instead.
            throw new IllegalStateException("recursive load of key " + key
                    + ": a loader must not request the key it is loading");
        }

        coalesced.increment();      // counted BEFORE blocking, so observers can tell we have arrived
        try {
            call.done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();          // never swallow an interrupt
            throw new CacheLoadException("interrupted while waiting for another thread to load " + key, e);
        }

        Throwable failure = call.failure;
        if (failure == null) {
            return call.value;
        }
        if (failure instanceof RuntimeException re) {
            throw re;                                     // the very exception the leader saw
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new CacheLoadException("load failed for key " + key, failure);
    }

    /** @return how many loaders have actually run (leaders only). */
    public long loads() {
        return loads.sum();
    }

    /** @return how many of those loads threw. */
    public long failures() {
        return failures.sum();
    }

    /** @return how many callers were spared a load by waiting on someone else's. */
    public long coalesced() {
        return coalesced.sum();
    }

    /** @return how many loads are in progress right now. Zero whenever the system is idle. */
    public int inFlightCount() {
        return inFlight.size();
    }
}
