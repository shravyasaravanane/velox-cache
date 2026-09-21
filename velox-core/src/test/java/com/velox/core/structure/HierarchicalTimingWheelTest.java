package com.velox.core.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchicalTimingWheelTest {

    private static Node<String, Integer> node(String key, long deadline) {
        var n = new Node<String, Integer>(key, 0, key.hashCode());
        n.setExpiresAtNanos(deadline);
        return n;
    }

    /** Drains everything due at {@code now}. */
    private static Set<Node<String, Integer>> drain(HierarchicalTimingWheel<String, Integer> wheel, long now) {
        Set<Node<String, Integer>> due = new HashSet<>();
        Node<String, Integer> n;
        while ((n = wheel.pollExpired(now)) != null) {
            due.add(n);
        }
        return due;
    }

    // ------------------------------------------------------------------
    //  Construction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalid geometry is rejected")
    void rejectsBadGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new HierarchicalTimingWheel<String, Integer>(0, 8, 0));
        assertThrows(IllegalArgumentException.class, () -> new HierarchicalTimingWheel<String, Integer>(1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new HierarchicalTimingWheel<String, Integer>(1, 6, 0));
    }

    @Test
    @DisplayName("an empty wheel returns nothing")
    void emptyWheel() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, 0);

        assertNull(wheel.pollExpired(1_000_000));
        assertEquals(0, wheel.size());
        wheel.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Exactness: never early, never late
    // ------------------------------------------------------------------

    @Test
    @DisplayName("nothing fires before its deadline, even within the same tick")
    void neverFiresEarly() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, 0);
        var n = node("A", 5_500);              // mid-way through tick 5
        wheel.schedule(n);

        assertNull(wheel.pollExpired(5_499), "1 ns before the deadline: must not fire");
        assertNull(wheel.pollExpired(5_000), "the start of the same tick: must not fire");
        assertSame(n, wheel.pollExpired(5_500), "exactly at the deadline: must fire");
        assertEquals(0, wheel.size());
        wheel.assertInvariants();
    }

    @Test
    @DisplayName("two deadlines inside one tick fire separately, each at its own instant")
    void resolvesInsideATick() {
        // Production wheels treat a whole tick as a single instant. This one does
        // not, because firing a live cache entry early would lose data.
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, 0);
        var early = node("early", 5_100);
        var late = node("late", 5_900);
        wheel.schedule(early);
        wheel.schedule(late);

        assertEquals(Set.of(early), drain(wheel, 5_500));
        assertEquals(1, wheel.size());
        assertEquals(Set.of(late), drain(wheel, 5_900));
        wheel.assertInvariants();
    }

    @Test
    @DisplayName("a deadline already in the past fires on the next poll")
    void overdueFiresImmediately() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, 0);
        wheel.pollExpired(50_000);            // move the hand well forward first
        var n = node("A", 1_000);             // long overdue

        wheel.schedule(n);

        assertSame(n, wheel.pollExpired(50_000));
        wheel.assertInvariants();
    }

    @Test
    @DisplayName("a timeout in a tick the hand has left behind still fires")
    void timeoutInAPassedTickIsNotStranded() {
        // The hand sits inside tick 5 while a deadline later in that same tick
        // is still pending. When time then jumps well past tick 5, that timeout
        // must be flushed as overdue. If it were forgotten, it would sit in slot 5
        // until the wheel lapped all the way round to it again.
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, 0);
        var n = node("A", 5_900);
        wheel.schedule(n);

        assertNull(wheel.pollExpired(5_100), "still 800 ns early: the hand is inside tick 5");
        assertSame(n, wheel.pollExpired(20_000), "tick 5 is long past, so the timeout is overdue");
        assertEquals(0, wheel.size());
        wheel.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Hierarchy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a two-slot wheel cascades correctly through many levels")
    void deepCascade() {
        // Size 2 is the harshest geometry: reaching tick 200 needs about eight
        // levels, so every timeout cascades repeatedly.
        var wheel = new HierarchicalTimingWheel<String, Integer>(1, 2, 0);
        var nodes = new ArrayList<Node<String, Integer>>();
        for (int d = 1; d <= 200; d++) {
            var n = node("t" + d, d);
            nodes.add(n);
            wheel.schedule(n);
        }
        wheel.assertInvariants();

        for (long now = 0; now <= 200; now++) {
            Set<Node<String, Integer>> fired = drain(wheel, now);
            Set<Node<String, Integer>> expected = new HashSet<>();
            for (var n : nodes) {
                if (n.expiresAtNanos() == now) {
                    expected.add(n);
                }
            }
            assertEquals(expected, fired, "wrong set fired at t=" + now);
            wheel.assertInvariants();
        }
        assertEquals(0, wheel.size());
    }

    @Test
    @DisplayName("timeouts far in the future are held until their time")
    void distantTimeouts() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1_000_000, 64, 0);   // 1 ms ticks
        var second = node("1s", TimeUnit.SECONDS.toNanos(1));
        var hour = node("1h", TimeUnit.HOURS.toNanos(1));
        var day = node("1d", TimeUnit.DAYS.toNanos(1));
        wheel.schedule(second);
        wheel.schedule(hour);
        wheel.schedule(day);
        wheel.assertInvariants();

        assertEquals(Set.of(second), drain(wheel, TimeUnit.SECONDS.toNanos(2)));
        assertEquals(Set.of(), drain(wheel, TimeUnit.MINUTES.toNanos(59)));
        assertEquals(Set.of(hour), drain(wheel, TimeUnit.HOURS.toNanos(1)));
        assertEquals(Set.of(day), drain(wheel, TimeUnit.DAYS.toNanos(2)));
        wheel.assertInvariants();
    }

    @Test
    @DisplayName("a ten-year jump completes instantly instead of stepping tick by tick")
    void skipsEmptyTime() {
        // With 1 ms ticks, ten years is 3e11 ticks. Advancing one at a time would
        // run for minutes; skipping straight to the next possible event does not.
        var wheel = new HierarchicalTimingWheel<String, Integer>(1_000_000, 64, 0);
        long year = TimeUnit.DAYS.toNanos(365);
        var soon = node("soon", TimeUnit.SECONDS.toNanos(5));
        var fiveYears = node("5y", 5 * year);
        var twelveYears = node("12y", 12 * year);
        wheel.schedule(soon);
        wheel.schedule(fiveYears);
        wheel.schedule(twelveYears);

        // If skipping ever breaks, this loop would step through 3e11 ticks and never
        // finish. A preemptive timeout turns that hang into an ordinary failure,
        // instead of a test run that has to be killed by hand.
        long started = System.nanoTime();
        Set<Node<String, Integer>> fired = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> drain(wheel, 10 * year),
                "advancing ten years did not finish: the wheel is stepping tick by tick");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(Set.of(soon, fiveYears), fired);
        assertEquals(1, wheel.size(), "the 12-year timeout is still pending");
        assertTrue(elapsedMs < 1_000, "jumping ten years took " + elapsedMs + " ms -- it is stepping tick by tick");
        wheel.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Cancel and reschedule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cancelled timeout never fires")
    void cancelPreventsFiring() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, 0);
        var n = node("A", 3_000);
        wheel.schedule(n);

        assertTrue(wheel.cancel(n));

        assertFalse(wheel.contains(n));
        assertEquals(0, wheel.size());
        assertNull(wheel.pollExpired(1_000_000));
        wheel.assertInvariants();
    }

    @Test
    @DisplayName("cancelling something that is not tracked is harmless")
    void cancelAbsent() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, 0);

        assertFalse(wheel.cancel(node("ghost", 5)));
        wheel.assertInvariants();
    }

    @Test
    @DisplayName("a timeout can be cancelled from any level")
    void cancelFromCoarseLevel() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1, 2, 0);
        var far = node("far", 500);
        wheel.schedule(far);

        assertTrue(wheel.cancel(far));

        assertEquals(0, wheel.size());
        wheel.assertInvariants();
    }

    @Test
    @DisplayName("rescheduling moves a timeout to its new time")
    void reschedule() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, 0);
        var n = node("A", 3_000);
        wheel.schedule(n);

        n.setExpiresAtNanos(90_000);          // pushed far later
        wheel.schedule(n);

        assertEquals(1, wheel.size(), "rescheduling must not duplicate the entry");
        assertEquals(Set.of(), drain(wheel, 50_000), "it no longer fires at its old time");
        assertEquals(Set.of(n), drain(wheel, 90_000));
        wheel.assertInvariants();
    }

    @Test
    @DisplayName("clear forgets every timeout")
    void clear() {
        var wheel = new HierarchicalTimingWheel<String, Integer>(1, 2, 0);
        var nodes = new ArrayList<Node<String, Integer>>();
        for (int d = 1; d < 100; d++) {
            var n = node("t" + d, d);
            nodes.add(n);
            wheel.schedule(n);
        }
        wheel.pollExpired(10);                // leave some in the ready list too

        wheel.clear();

        assertEquals(0, wheel.size());
        for (var n : nodes) {
            assertFalse(wheel.contains(n));
        }
        assertNull(wheel.pollExpired(1_000));
        wheel.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Clock wrap-around
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keeps working when the nanosecond counter wraps past Long.MAX_VALUE")
    void survivesClockWrap() {
        // Deriving slot indexes from absolute nanoTime values would break at the
        // wrap. Counting ticks since a recorded start, by subtraction, does not.
        long start = Long.MAX_VALUE - 10_000;
        var wheel = new HierarchicalTimingWheel<String, Integer>(1000, 8, start);
        var n = node("A", start + 50_000);    // this addition wraps to a negative number
        assertTrue(n.expiresAtNanos() < 0, "the test setup must actually wrap");
        wheel.schedule(n);

        assertNull(wheel.pollExpired(start + 49_999));
        assertSame(n, wheel.pollExpired(start + 50_000));
        wheel.assertInvariants();
    }

    // ------------------------------------------------------------------
    //  Differential test
    // ------------------------------------------------------------------

    /**
     * Drives the wheel with random schedules, cancels, reschedules and time
     * jumps, and checks after every poll that it returned exactly the timeouts
     * whose deadline has passed -- no more, no fewer.
     *
     * <p>The reference is a plain list scanned in full: O(n) and obviously right.
     */
    private static void differential(long tick, int wheelSize, long start, long seed) {
        var wheel = new HierarchicalTimingWheel<String, Integer>(tick, wheelSize, start);
        List<Node<String, Integer>> reference = new ArrayList<>();
        var random = new Random(seed);
        long now = start;
        int counter = 0;
        // Deadlines and time jumps are measured in quarter-ticks so that they are
        // NOT aligned to tick boundaries. The floor of 1 matters: for tick=1 a
        // plain tick/4 is 0, which would make every deadline "now" and the test vacuous.
        final long unit = Math.max(1, tick / 4);

        for (int step = 0; step < 15_000; step++) {
            int action = random.nextInt(10);

            if (action < 4) {
                // Deadlines land anywhere from slightly in the past to far ahead,
                // deliberately not aligned to tick boundaries.
                long delta = random.nextInt(8) == 0 ? random.nextInt(200_000) : random.nextInt(4_000) - 100;
                var n = node("k" + counter++, now + delta * unit);
                wheel.schedule(n);
                reference.add(n);

            } else if (action == 4 && !reference.isEmpty()) {
                var n = reference.remove(random.nextInt(reference.size()));
                assertTrue(wheel.cancel(n));

            } else if (action == 5 && !reference.isEmpty()) {
                var n = reference.get(random.nextInt(reference.size()));
                n.setExpiresAtNanos(now + random.nextInt(6_000) * unit);
                wheel.schedule(n);

            } else {
                // Move time forward, occasionally by a huge amount, then drain.
                long jump = random.nextInt(20) == 0 ? random.nextInt(2_000_000) : random.nextInt(600);
                now += jump * unit;
                Set<Node<String, Integer>> fired = drain(wheel, now);

                Set<Node<String, Integer>> expected = new HashSet<>();
                for (var n : reference) {
                    if (now - n.expiresAtNanos() >= 0) {     // subtraction: wrap-safe, like the wheel
                        expected.add(n);
                    }
                }
                assertEquals(expected, fired, "wrong set fired at step " + step + " (tick=" + tick
                        + ", size=" + wheelSize + ")");
                reference.removeAll(expected);
            }

            assertEquals(reference.size(), wheel.size(), "size diverged at step " + step);
            wheel.assertInvariants();
        }
    }

    @Test
    @DisplayName("matches a brute-force reference: two slots per level (deepest hierarchy)")
    void differentialSize2() {
        differential(1, 2, 0, 1);
    }

    @Test
    @DisplayName("matches a brute-force reference: four slots, an awkward tick width")
    void differentialSize4() {
        differential(7, 4, 0, 2);
    }

    @Test
    @DisplayName("matches a brute-force reference: eight slots")
    void differentialSize8() {
        differential(1000, 8, 0, 3);
    }

    @Test
    @DisplayName("matches a brute-force reference: the default 64 slots")
    void differentialSize64() {
        differential(1_000_000, 64, 0, 4);
    }

    @Test
    @DisplayName("matches a brute-force reference while the clock wraps around mid-run")
    void differentialAcrossClockWrap() {
        // Starting 5,000 ns before Long.MAX_VALUE means the run crosses the wrap
        // almost immediately, so most of it happens on the far side of it.
        differential(3, 4, Long.MAX_VALUE - 5_000, 5);
        differential(1000, 16, Long.MAX_VALUE - 5_000, 6);
    }
}
