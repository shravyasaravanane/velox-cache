package com.velox.core.expiry;

import com.velox.core.util.Ticker;

import java.time.Duration;

/**
 * A clock that only moves when the test says so.
 *
 * <p>Also counts how many times it was read, which lets a test prove that a
 * cache without TTLs never touches the clock at all.
 */
public final class FakeTicker implements Ticker {

    private long nanos;
    private int reads;

    public FakeTicker() {
        this(0);
    }

    public FakeTicker(long startNanos) {
        this.nanos = startNanos;
    }

    @Override
    public long read() {
        reads++;
        return nanos;
    }

    public FakeTicker advance(Duration duration) {
        nanos += duration.toNanos();
        return this;
    }

    public FakeTicker advanceNanos(long delta) {
        nanos += delta;
        return this;
    }

    /** @return how many times the cache has read this clock */
    public int reads() {
        return reads;
    }
}
