package io.github.tomerg12.fleetlink.server.rating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Locks the frozen Elo examples, rounding policy, and minimum-rating clamp.
 */
class EloRatingCalculatorTest {

    /**
     * Verifies the required equal and unequal deterministic rating examples.
     */
    @Test
    void calculatesLockedExamples() {
        assertEquals(16, EloRatingCalculator.ratingDelta(1000, 1000, true));
        assertEquals(-16, EloRatingCalculator.ratingDelta(1000, 1000, false));
        assertEquals(8, EloRatingCalculator.ratingDelta(1200, 1000, true));
        assertEquals(-8, EloRatingCalculator.ratingDelta(1000, 1200, false));
        assertEquals(24, EloRatingCalculator.ratingDelta(1000, 1200, true));
        assertEquals(-24, EloRatingCalculator.ratingDelta(1200, 1000, false));
    }

    /**
     * Stores the actual clamped transition instead of an unclamped theoretical delta.
     */
    @Test
    void clampsAtZeroAndReturnsActualDelta() {
        assertEquals(0, EloRatingCalculator.ratingAfter(1, 0, false));
        assertEquals(-1, EloRatingCalculator.ratingDelta(1, 0, false));
    }

    /**
     * Rejects impossible negative rating inputs.
     */
    @Test
    void rejectsNegativeRatings() {
        assertThrows(IllegalArgumentException.class,
                () -> EloRatingCalculator.ratingAfter(-1, 1000, true));
        assertThrows(IllegalArgumentException.class,
                () -> EloRatingCalculator.ratingAfter(1000, -1, true));
    }
}
