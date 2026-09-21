package com.velox.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DrainStatusTest {

    @Test
    @DisplayName("a request on an idle system says 'run a drain' and records that one is wanted")
    void requestWhenIdle() {
        var status = new DrainStatus();
        assertEquals(DrainStatus.IDLE, status.state());

        assertTrue(status.requestDrain());

        assertEquals(DrainStatus.REQUIRED, status.state());
    }

    @Test
    @DisplayName("further requests while one is pending also say 'run a drain'")
    void requestWhenAlreadyRequired() {
        var status = new DrainStatus();
        status.requestDrain();

        assertTrue(status.requestDrain(), "several threads may race to run it; the lock picks the winner");
        assertEquals(DrainStatus.REQUIRED, status.state());
    }

    @Test
    @DisplayName("a drain that finds nothing more asked of it returns to idle")
    void cleanFinish() {
        var status = new DrainStatus();
        status.requestDrain();

        status.beginProcessing();
        assertEquals(DrainStatus.PROCESSING_TO_IDLE, status.state());

        assertFalse(status.endProcessing(), "nobody asked again, so no second pass is needed");
        assertEquals(DrainStatus.IDLE, status.state());
    }

    @Test
    @DisplayName("a request that arrives DURING a drain is remembered and forces another pass")
    void requestDuringDrainIsNotLost() {
        // The lost-wakeup case. Without the extra state the request would be ignored
        // ("someone is already draining"), the drain would finish without having seen the
        // new items, and the buffer would sit full with nothing left to empty it.
        var status = new DrainStatus();
        status.requestDrain();
        status.beginProcessing();

        assertFalse(status.requestDrain(), "the running drain will handle it; the caller need not");
        assertEquals(DrainStatus.PROCESSING_TO_REQUIRED, status.state());

        assertTrue(status.endProcessing(), "the drain must go round again");
        assertEquals(DrainStatus.REQUIRED, status.state());
    }

    @Test
    @DisplayName("repeated requests during one drain still cost only one extra pass")
    void manyRequestsDuringDrain() {
        var status = new DrainStatus();
        status.requestDrain();
        status.beginProcessing();

        for (int i = 0; i < 50; i++) {
            assertFalse(status.requestDrain());
        }

        assertTrue(status.endProcessing());
        status.beginProcessing();
        assertFalse(status.endProcessing(), "and after that extra pass, it is quiet again");
        assertEquals(DrainStatus.IDLE, status.state());
    }

    // ------------------------------------------------------------------
    //  Against an explicit model
    // ------------------------------------------------------------------

    /** The state machine written out as a plain transition table, independently of the class. */
    private static final class Model {
        int state = DrainStatus.IDLE;

        boolean request() {
            return switch (state) {
                case DrainStatus.IDLE -> {
                    state = DrainStatus.REQUIRED;
                    yield true;
                }
                case DrainStatus.REQUIRED -> true;
                case DrainStatus.PROCESSING_TO_IDLE -> {
                    state = DrainStatus.PROCESSING_TO_REQUIRED;
                    yield false;
                }
                default -> false;
            };
        }

        void begin() {
            state = DrainStatus.PROCESSING_TO_IDLE;
        }

        boolean end() {
            if (state == DrainStatus.PROCESSING_TO_IDLE) {
                state = DrainStatus.IDLE;
                return false;
            }
            state = DrainStatus.REQUIRED;
            return true;
        }
    }

    @Test
    @DisplayName("matches an explicit transition table over 200,000 random operations")
    void differentialAgainstTransitionTable() {
        var real = new DrainStatus();
        var model = new Model();
        var random = new Random(17);
        boolean processing = false;

        for (int step = 0; step < 200_000; step++) {
            int action = random.nextInt(3);
            if (action == 0) {
                assertEquals(model.request(), real.requestDrain(), "request diverged at step " + step);
            } else if (action == 1 && !processing) {
                model.begin();
                real.beginProcessing();
                processing = true;
            } else if (action == 2 && processing) {
                assertEquals(model.end(), real.endProcessing(), "end diverged at step " + step);
                processing = false;
            }
            assertEquals(model.state, real.state(), "state diverged at step " + step);
        }
    }

    // ------------------------------------------------------------------
    //  Concurrent: no request is ever stranded
    // ------------------------------------------------------------------

    @Test
    @DisplayName("with racing requesters and a draining thread, every request is served by some drain")
    void noRequestIsStranded() throws Exception {
        // Model: each requester bumps `requested` and asks for a drain. The drainer, each
        // time it runs, records how many requests it had observed. If the state machine
        // could lose a request, then after everything quiesces, `served` would fall short
        // of `requested` and stay short.
        final int requesters = 6;
        final int perRequester = 100_000;
        var status = new DrainStatus();
        var requested = new java.util.concurrent.atomic.AtomicLong();
        var served = new java.util.concurrent.atomic.AtomicLong();
        var lock = new java.util.concurrent.locks.ReentrantLock();
        var start = new CountDownLatch(1);
        var done = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(requesters);

        Runnable tryDrain = () -> {
            if (lock.tryLock()) {
                try {
                    do {
                        status.beginProcessing();
                        served.set(requested.get());       // "process everything requested so far"
                    } while (status.endProcessing());
                } finally {
                    lock.unlock();
                }
            }
        };

        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int r = 0; r < requesters; r++) {
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perRequester; i++) {
                        requested.incrementAndGet();
                        if (status.requestDrain()) {
                            tryDrain.run();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(50, TimeUnit.SECONDS);
            }
            done.set(true);
        } finally {
            pool.shutdownNow();
        }

        // Quiescent now. One more request (as the next real read or write would make)
        // must be enough to bring everything up to date: the machine may leave a drain
        // PENDING, but it must never lose track that one is wanted.
        assertTrue(status.state() == DrainStatus.IDLE || status.state() == DrainStatus.REQUIRED,
                "an invalid or stuck state after quiescence: " + status.state());
        if (status.requestDrain()) {
            tryDrain.run();
        }
        assertEquals(requested.get(), served.get(), "a request was lost");
        assertEquals(DrainStatus.IDLE, status.state());
    }
}
