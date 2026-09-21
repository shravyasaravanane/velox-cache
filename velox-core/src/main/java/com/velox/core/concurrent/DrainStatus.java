package com.velox.core.concurrent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates "someone should drain the read buffer" without ever losing the request.
 *
 * <h2>The lost-wakeup problem</h2>
 *
 * Suppose a reader fills the buffer and asks for a drain, and a drain is already in
 * progress. If that request were simply ignored ("someone is already draining"), the
 * running drain might have already passed the point where it would have seen the new
 * items; it would finish, the buffer would sit full, and nothing would ever drain it
 * again until the next write. Reads would then be dropped indefinitely. A request that
 * arrives <i>while</i> a drain is running has to be remembered and acted on when the
 * drain ends.
 *
 * <h2>The four states</h2>
 *
 * <pre>
 *   IDLE                   nothing to do
 *   REQUIRED               a drain is wanted and nobody is running one
 *   PROCESSING_TO_IDLE     a drain is running; if nothing else asks, we go IDLE after
 *   PROCESSING_TO_REQUIRED a drain is running AND another was requested meanwhile
 *
 *   reader asks:      IDLE -> REQUIRED                      (caller should run a drain)
 *                     PROCESSING_TO_IDLE -> PROCESSING_TO_REQUIRED   (remember it)
 *   drain begins:     ... -> PROCESSING_TO_IDLE
 *   drain ends:       PROCESSING_TO_IDLE -> IDLE            (done)
 *                     PROCESSING_TO_REQUIRED -> REQUIRED    (run it again)
 * </pre>
 *
 * The transitions are compare-and-set on a single integer, so no lock is needed to
 * coordinate the coordination.
 */
public final class DrainStatus {

    public static final int IDLE = 0;
    public static final int REQUIRED = 1;
    public static final int PROCESSING_TO_IDLE = 2;
    public static final int PROCESSING_TO_REQUIRED = 3;

    private final AtomicInteger state = new AtomicInteger(IDLE);

    /**
     * A reader (or anyone) wants a drain to happen.
     *
     * @return {@code true} if the caller should try to run a drain now; {@code false} if
     *         one is already running and has been told to go round again
     * @implNote O(1), lock-free
     */
    public boolean requestDrain() {
        for (;;) {
            int current = state.get();
            switch (current) {
                case IDLE -> {
                    if (state.compareAndSet(IDLE, REQUIRED)) {
                        return true;
                    }
                }
                case REQUIRED -> {
                    return true;
                }
                case PROCESSING_TO_IDLE -> {
                    if (state.compareAndSet(PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED)) {
                        return false;
                    }
                }
                default -> {
                    return false;               // PROCESSING_TO_REQUIRED: already remembered
                }
            }
        }
    }

    /** Called by the thread that is about to drain (and holds the lock). */
    public void beginProcessing() {
        state.set(PROCESSING_TO_IDLE);
    }

    /**
     * Called when a drain finishes.
     *
     * @return {@code true} if another request arrived during the drain, so the caller
     *         must drain again; {@code false} if the system is now idle
     */
    public boolean endProcessing() {
        if (state.compareAndSet(PROCESSING_TO_IDLE, IDLE)) {
            return false;
        }
        state.set(REQUIRED);                    // it was PROCESSING_TO_REQUIRED: run again
        return true;
    }

    /** @return the current state, one of the constants above */
    public int state() {
        return state.get();
    }
}
