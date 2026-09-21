package com.velox.core.util;

/**
 * A source of time, in nanoseconds.
 *
 * <h2>Why the cache does not just call System.nanoTime()</h2>
 *
 * Expiry logic is all about time, and code that reads the real clock cannot be
 * tested properly. To check "an entry expires exactly 10 seconds after it was
 * written" you would have to actually wait 10 seconds -- and even then you
 * could never test the boundary, because you cannot make the real clock stop
 * at exactly 9.999999999 seconds.
 *
 * <p>So the cache asks a {@code Ticker} for the time, and tests hand it a fake
 * one that only moves when told to. Time becomes just another input, and a
 * test that would take ten seconds runs in microseconds and hits every
 * boundary exactly.
 *
 * <h2>Why nanoTime and not the wall clock</h2>
 *
 * {@link System#currentTimeMillis()} is the calendar time, and it can jump
 * backwards -- an NTP correction, a daylight-saving change, someone setting
 * the system clock. A backwards jump would resurrect entries that had already
 * expired. {@link System#nanoTime()} is a monotonic counter that only moves
 * forward, which is exactly what a timeout needs.
 *
 * <p>Its values are only meaningful <i>relative to each other</i> (the origin
 * is arbitrary and can even be negative), and the counter may wrap around
 * after roughly 292 years. That is why deadlines are always compared by
 * <b>subtraction</b> ({@code now - deadline >= 0}) rather than with
 * {@code <}: subtraction stays correct across a wrap, direct comparison does
 * not.
 */
@FunctionalInterface
public interface Ticker {

    /** @return the current time in nanoseconds; only differences are meaningful */
    long read();

    /** @return a ticker backed by the JVM's monotonic clock */
    static Ticker system() {
        return System::nanoTime;
    }
}
