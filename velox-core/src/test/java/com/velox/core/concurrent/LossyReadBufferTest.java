package com.velox.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 90, unit = TimeUnit.SECONDS)
class LossyReadBufferTest {

    private static List<Integer> drainAll(LossyReadBuffer<Integer> buffer) {
        var out = new ArrayList<Integer>();
        buffer.drain(out::add);
        return out;
    }

    // ------------------------------------------------------------------
    //  Single-threaded behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("capacity must be a power of two, at least 2")
    void rejectsBadCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LossyReadBuffer<Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new LossyReadBuffer<Integer>(1));
        assertThrows(IllegalArgumentException.class, () -> new LossyReadBuffer<Integer>(6));
    }

    @Test
    @DisplayName("items come back oldest first, and draining empties the buffer")
    void fifoOrder() {
        var buffer = new LossyReadBuffer<Integer>(8);
        for (int i = 1; i <= 5; i++) {
            buffer.offer(i);
        }

        assertEquals(5, buffer.size());
        assertEquals(List.of(1, 2, 3, 4, 5), drainAll(buffer));
        assertEquals(0, buffer.size());
        assertEquals(List.of(), drainAll(buffer));
    }

    @Test
    @DisplayName("a full buffer drops new items rather than blocking or overwriting")
    void fullBufferDrops() {
        var buffer = new LossyReadBuffer<Integer>(4);
        for (int i = 1; i <= 4; i++) {
            assertTrue(buffer.offer(i) <= LossyReadBuffer.ACCEPTED_DRAIN_ADVISED);
        }

        assertEquals(LossyReadBuffer.DROPPED_FULL, buffer.offer(99));
        assertEquals(LossyReadBuffer.DROPPED_FULL, buffer.offer(100));

        assertEquals(2, buffer.dropped());
        assertEquals(List.of(1, 2, 3, 4), drainAll(buffer), "the old items must be intact: nothing was overwritten");
    }

    @Test
    @DisplayName("once drained, a full buffer accepts items again")
    void reusableAfterDrain() {
        var buffer = new LossyReadBuffer<Integer>(4);
        for (int i = 0; i < 4; i++) {
            buffer.offer(i);
        }
        drainAll(buffer);

        assertEquals(LossyReadBuffer.ACCEPTED, buffer.offer(50));
        assertEquals(List.of(50), drainAll(buffer));
    }

    @Test
    @DisplayName("order and content survive many trips round the ring")
    void wrapsAroundCorrectly() {
        var buffer = new LossyReadBuffer<Integer>(8);
        int next = 0;
        var expected = new ArrayList<Integer>();
        var actual = new ArrayList<Integer>();

        for (int round = 0; round < 500; round++) {
            int batch = 1 + (round % 8);                     // 1..8 items, up to exactly full
            for (int i = 0; i < batch; i++) {
                buffer.offer(next);
                expected.add(next);
                next++;
            }
            buffer.drain(actual::add);
        }

        assertEquals(expected, actual);
        assertEquals(0, buffer.dropped(), "never more than capacity between drains, so nothing may drop");
    }

    @Test
    @DisplayName("the buffer advises draining once it is half full")
    void adviceThreshold() {
        var buffer = new LossyReadBuffer<Integer>(8);

        assertEquals(LossyReadBuffer.ACCEPTED, buffer.offer(1));
        assertEquals(LossyReadBuffer.ACCEPTED, buffer.offer(2));
        assertEquals(LossyReadBuffer.ACCEPTED, buffer.offer(3));
        assertEquals(LossyReadBuffer.ACCEPTED_DRAIN_ADVISED, buffer.offer(4), "4 of 8: time to drain");
        assertEquals(LossyReadBuffer.ACCEPTED_DRAIN_ADVISED, buffer.offer(5));
    }

    @Test
    @DisplayName("a consumer that throws does not wedge the buffer")
    void throwingConsumerDoesNotWedge() {
        var buffer = new LossyReadBuffer<Integer>(8);
        for (int i = 1; i <= 4; i++) {
            buffer.offer(i);
        }
        var seen = new ArrayList<Integer>();

        assertThrows(IllegalStateException.class, () -> buffer.drain(item -> {
            seen.add(item);
            if (item == 2) {
                throw new IllegalStateException("consumer bug");
            }
        }));

        // Items 1 and 2 were consumed (2 is lost by design: consumers must not throw),
        // but the buffer must not be stuck or corrupted: 3 and 4 are still delivered.
        assertEquals(List.of(3, 4), drainAll(buffer));
        assertEquals(LossyReadBuffer.ACCEPTED, buffer.offer(9));
        assertEquals(List.of(9), drainAll(buffer));
    }

    // ------------------------------------------------------------------
    //  Concurrent behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("under heavy multi-producer load: nothing invented, nothing duplicated, everything accounted for")
    void multiProducerStress() throws Exception {
        final int producers = 8;
        final int perProducer = 250_000;
        var buffer = new LossyReadBuffer<Long>(64);
        var offered = new AtomicLong();
        var producersDone = new AtomicBoolean();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(producers + 1);

        // Items are (producer id, sequence number), so the consumer can check both that
        // each is seen at most once and that each producer's items stay in order.
        long[] lastSeen = new long[producers];
        java.util.Arrays.fill(lastSeen, -1);
        var consumed = new AtomicLong();
        var problems = new ArrayList<String>();

        try {
            Future<?> consumer = pool.submit(() -> {
                start.await();
                java.util.function.Consumer<Long> take = item -> {
                    int producer = (int) (item >>> 32);
                    long sequence = item & 0xFFFFFFFFL;
                    if (producer < 0 || producer >= producers || sequence >= perProducer) {
                        problems.add("invented item " + item);
                    }
                    if (sequence <= lastSeen[producer]) {
                        problems.add("producer " + producer + " went backwards: " + sequence
                                + " after " + lastSeen[producer]);
                    }
                    lastSeen[producer] = sequence;      // strictly increasing also rules out duplicates
                    consumed.incrementAndGet();
                };
                while (!producersDone.get()) {
                    buffer.drain(take);                   // one consumer, as the contract requires
                }
                while (buffer.drain(take) > 0) {
                    // final sweep after the producers stop
                }
                return null;
            });

            List<Future<?>> writers = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                final long id = p;
                writers.add(pool.submit(() -> {
                    start.await();
                    for (long seq = 0; seq < perProducer; seq++) {
                        buffer.offer((id << 32) | seq);
                        offered.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(80, TimeUnit.SECONDS);
            }
            producersDone.set(true);
            consumer.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(problems.isEmpty(), problems.stream().limit(5).toList().toString());
        assertEquals((long) producers * perProducer, offered.get());
        assertEquals(offered.get(), consumed.get() + buffer.dropped(),
                "every offer was either consumed or counted as dropped: none vanished, none appeared");
        assertEquals(0, buffer.size());
        assertFalse(consumed.get() == 0, "the consumer must have received something");
        System.out.printf("  [lossy buffer] %d offers: %d consumed, %d dropped (%.1f%%)%n",
                offered.get(), consumed.get(), buffer.dropped(), 100.0 * buffer.dropped() / offered.get());
    }
}
