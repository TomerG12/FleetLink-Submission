package io.github.tomerg12.fleetlink.server.rating;

/**
 * Calculates deterministic registered-player Elo transitions using the frozen FleetLink policy.
 */
public final class EloRatingCalculator {

    /** The initial rating assigned to every registered account. */
    public static final int INITIAL_RATING = 1000;

    /** The fixed rating used for temporary guest matchmaking. */
    public static final int GUEST_RATING = 1000;

    /** The fixed Elo K-factor used by every rated game. */
    public static final int K_FACTOR = 32;

    /** The minimum permitted registered rating. */
    public static final int MINIMUM_RATING = 0;

    private static final double ELO_SCALE = 400.0;

    /**
     * Prevents construction because the rating policy is stateless.
     */
    private EloRatingCalculator() {
    }

    /**
     * Calculates the stored rating after one decisive game result.
     *
     * @param rating participant rating captured at match creation
     * @param opponentRating opponent rating captured at match creation
     * @param winner true when the participant won, false when the participant lost
     * @return nearest integer Elo result clamped to the minimum rating
     * @throws IllegalArgumentException if either supplied rating is negative
     * @throws ArithmeticException if the result exceeds the current integer protocol range
     */
    public static int ratingAfter(int rating, int opponentRating, boolean winner) {
        validateRating(rating, "rating");
        validateRating(opponentRating, "opponentRating");
        double expected = 1.0 / (1.0
                + Math.pow(10.0, ((double) opponentRating - rating) / ELO_SCALE));
        double score = winner ? 1.0 : 0.0;
        long rounded = Math.round(rating + K_FACTOR * (score - expected));
        return Math.toIntExact(Math.max(MINIMUM_RATING, rounded));
    }

    /**
     * Calculates the actual stored delta after rounding and minimum-rating clamping.
     *
     * @param rating participant rating captured at match creation
     * @param opponentRating opponent rating captured at match creation
     * @param winner true when the participant won, false when the participant lost
     * @return stored rating transition delta
     * @throws IllegalArgumentException if either supplied rating is negative
     */
    public static int ratingDelta(int rating, int opponentRating, boolean winner) {
        return ratingAfter(rating, opponentRating, winner) - rating;
    }

    /**
     * Rejects an invalid rating before floating-point policy calculation.
     *
     * @param rating rating value to validate
     * @param name parameter name used in the failure message
     */
    private static void validateRating(int rating, String name) {
        if (rating < MINIMUM_RATING) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
