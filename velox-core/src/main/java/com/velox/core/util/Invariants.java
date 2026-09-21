package com.velox.core.util;

/**
 * Internal consistency checking for our data structures.
 *
 * <h2>What an "invariant" is</h2>
 *
 * An invariant is a statement that must be true <i>at all times</i>, no matter
 * what operations have run. For our linked list, for example:
 * "walking forwards gives the same number of nodes as walking backwards."
 *
 * <p>Pointer-based structures fail in a nasty way: a broken pointer usually
 * does not crash. The list just silently loses entries, or grows a loop, and
 * the cache starts returning wrong answers. You discover it hours later, in a
 * test that looks unrelated.
 *
 * <p>So every structure we write exposes {@code assertInvariants()}, and our
 * tests call it after <i>every single operation</i>. The moment a pointer
 * breaks, the very next check throws — pointing straight at the operation
 * that caused it, instead of at some distant symptom.
 *
 * <h2>Why it is switchable</h2>
 *
 * These checks walk the whole structure, so they are O(n) — far too slow to
 * run in production, where our operations must be O(1). The flag lets us have
 * both: thorough checking while testing, zero cost when deployed.
 *
 * <p>{@link #ENABLED} is {@code static final}, read once at class-load time.
 * That matters: the JIT compiler sees a constant {@code false} in production
 * and deletes every check from the compiled code entirely. The safety is
 * genuinely free.
 *
 * <p>Turn it on with {@code -Dvelox.assertions=true} (our Maven test config
 * already does this — see the parent {@code pom.xml}).
 */
public final class Invariants {

    /** Whether internal consistency checks should run. */
    public static final boolean ENABLED = Boolean.getBoolean("velox.assertions");

    private Invariants() {
        // Utility class: never instantiated.
    }

    /**
     * Throws if {@code condition} is false.
     *
     * @param condition the thing that must be true
     * @param message   what went wrong, for the error report
     * @throws IllegalStateException if the invariant is violated
     */
    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("VeloxCache invariant violated: " + message);
        }
    }
}
