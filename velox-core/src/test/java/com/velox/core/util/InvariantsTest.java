package com.velox.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Invariants} — and a guard against a silent failure.
 */
class InvariantsTest {

    /**
     * This test exists to stop the entire safety net switching itself off.
     *
     * <p>Every structure's {@code assertInvariants()} starts with
     * {@code if (!Invariants.ENABLED) return;}. If the system property is
     * ever lost — someone edits the pom, runs the tests from an IDE without
     * the flag, or renames the property — then every one of those checks
     * becomes a no-op.
     *
     * <p>Nothing would fail. The build would stay green. We would simply stop
     * verifying our data structures, and would not find out until a corrupted
     * cache showed up somewhere far away.
     *
     * <p>So we assert the flag itself. If the checks are off, this test fails
     * loudly and tells you how to switch them back on.
     */
    @Test
    @DisplayName("invariant checking is switched ON for the test run")
    void invariantCheckingIsEnabledDuringTests() {
        assertTrue(Invariants.ENABLED,
                "Invariant checks are DISABLED, so assertInvariants() is doing nothing. "
                        + "Run tests with -Dvelox.assertions=true (the parent pom's "
                        + "surefire configuration normally sets this for you).");
    }

    @Test
    @DisplayName("check() passes silently when the condition holds")
    void checkPassesWhenConditionIsTrue() {
        Invariants.check(true, "this should never be thrown");
    }

    @Test
    @DisplayName("check() throws with a helpful message when the condition fails")
    void checkThrowsWhenConditionIsFalse() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> Invariants.check(false, "the list ate itself"));

        assertTrue(thrown.getMessage().contains("the list ate itself"),
                "the failure message must explain what actually broke");
    }
}
