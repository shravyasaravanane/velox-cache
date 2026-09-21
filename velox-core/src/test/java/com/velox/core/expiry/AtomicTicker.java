package com.velox.core.expiry;

import com.velox.core.util.Ticker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fake clock that is safe to read from many threads while another thread moves it.
 * (The plain {@link FakeTicker} is for single-threaded tests.)
 */
public final class AtomicTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
        return nanos.get();
    }

    public void advanceNanos(long delta) {
        nanos.addAndGet(delta);
    }

    public void advance(Duration duration) {
        advanceNanos(duration.toNanos());
    }
}
