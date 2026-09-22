package com.velox.server.livestats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A fixed-size, lock-free ring of recent request latencies, sized so the dashboard's latency
 * percentile bars (p50/p90/p99/p999) reflect roughly the last few seconds of traffic rather than
 * the application's entire lifetime -- a lifetime p99 would smear a brief spike across hours and
 * never move again.
 *
 * <h2>Why {@link AtomicLongArray}, and what it does not guarantee</h2>
 *
 * A plain {@code long[]} does not guarantee an element write is atomic under the Java Memory
 * Model without external synchronization; {@link AtomicLongArray} does, at no real cost here
 * (one {@code lazySet} per request). What it does <b>not</b> guarantee is temporal ordering
 * across a wrap: once more than {@code capacity} samples have been recorded, the buffer holds
 * the last {@code capacity} writes in whatever order their threads happened to land, not
 * strictly oldest-to-newest. That is irrelevant for a percentile, which only cares which values
 * are present, not their order -- and it is exactly what a fixed-size "reservoir of the most
 * recent N" is for.
 *
 * <p>One benign race is possible under very high concurrency: two threads computing slot indices
 * exactly {@code capacity} apart can race to write the same slot, and the loser's sample is
 * silently overwritten rather than landing in the neighbouring slot. This is best-effort
 * telemetry for a live dashboard, not a correctness-critical measurement -- see
 * {@code docs/benchmarks/} for where this project's actual measured claims live.
 */
@Component
public class LatencyRingBuffer {

    private final AtomicLongArray buffer;
    private final AtomicLong nextIndex = new AtomicLong();

    public LatencyRingBuffer(@Value("${velox.demo.latency-buffer-size:2048}") int capacity) {
        this.buffer = new AtomicLongArray(capacity);
    }

    /** Records one request's latency, in nanoseconds. */
    public void record(long elapsedNanos) {
        int slot = (int) (nextIndex.getAndIncrement() % buffer.length());
        buffer.lazySet(slot, elapsedNanos);
    }

    /**
     * @return every currently-held sample, sorted ascending, in milliseconds -- fewer than the
     *         buffer's capacity until it has wrapped once
     */
    long[] snapshotSortedMillis() {
        long written = nextIndex.get();
        int count = (int) Math.min(written, buffer.length());
        long[] millis = new long[count];
        for (int i = 0; i < count; i++) {
            millis[i] = buffer.get(i) / 1_000_000L;
        }
        Arrays.sort(millis);
        return millis;
    }

    /** @param fraction e.g. 0.50 for p50, 0.99 for p99 */
    public double percentileMillis(double fraction) {
        long[] sorted = snapshotSortedMillis();
        if (sorted.length == 0) {
            return 0.0;
        }
        int index = (int) Math.min(sorted.length - 1, Math.floor(fraction * sorted.length));
        return sorted[index];
    }
}
