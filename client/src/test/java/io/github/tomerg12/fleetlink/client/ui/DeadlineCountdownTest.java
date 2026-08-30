package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies countdown formatting is zero-clamped presentation only. */
class DeadlineCountdownTest {
    /** Formats positive remaining time and stays at zero after the deadline passes. */
    @Test
    void formatsRemainingTimeWithoutAuthoritativeSideEffects() {
        assertEquals("02:00", DeadlineCountdown.formatRemaining(120_000L, 0L));
        assertEquals("00:01", DeadlineCountdown.formatRemaining(1_001L, 1L));
        assertEquals("00:00", DeadlineCountdown.formatRemaining(1_000L, 1_000L));
        assertEquals("00:00", DeadlineCountdown.formatRemaining(1_000L, 2_000L));
    }
}
