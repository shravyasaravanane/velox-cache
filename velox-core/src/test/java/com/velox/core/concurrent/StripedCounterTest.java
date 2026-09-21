package com.velox.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StripedCounterTest {

    @Test
    @DisplayName("a single thread counts exactly")
    void singleThread() {
        var counter = new StripedCounter(4, true);

        for (int i = 0; i < 1_000; i++) {
            counter.increment();
        }
        counter.add(500);
        counter.add(-100);

        assertEquals(1_400, counter.sum());
    }

    @Test
    @DisplayName("the stripe count is rounded up to a power of two")
    void stripesRoundUp() {
        assertEquals(1, new StripedCounter(1, true).stripes());
        assertEquals(4, new StripedCounter(3, true).stripes());
        assertEquals(8, new StripedCounter(8, false).stripes());
        assertEquals(1, new StripedCounter(0, true).stripes(), "nonsense sizes collapse to the minimum");
    }

    @ParameterizedTest(name = "padded = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("no increment is ever lost, even with 16 threads hammering a few cells")
    void concurrentIncrementsAreExact(boolean padded) throws Exception {
        final int threads = 16;
        final int perThread = 200_000;
        // Deliberately FEWER stripes than threads, so several threads collide on the
        // same cell: that is exactly where a non-atomic add would lose updates.
        var counter = new StripedCounter(4, padded);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        counter.increment();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(50, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals((long) threads * perThread, counter.sum());
    }

    @Test
    @DisplayName("a sum taken while writers run never goes backwards and never overshoots")
    void sumIsMonotonicWhileWritersRun() throws Exception {
        final int writers = 6;
        final int perWriter = 300_000;
        var counter = new StripedCounter(8, true);
        var finished = new AtomicBoolean();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);

        try {
            Future<Boolean> reader = pool.submit(() -> {
                start.await();
                long previous = 0;
                boolean monotonic = true;
                while (!finished.get()) {
                    long now = counter.sum();
                    if (now < previous || now > (long) writers * perWriter) {
                        monotonic = false;
                    }
                    previous = now;
                }
                return monotonic;
            });
            List<Future<?>> workers = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        counter.increment();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(50, TimeUnit.SECONDS);
            }
            finished.set(true);

            assertTrue(reader.get(20, TimeUnit.SECONDS),
                    "a reader saw the count decrease or exceed the true total");
        } finally {
            pool.shutdownNow();
        }
        assertEquals((long) writers * perWriter, counter.sum());
    }
}
