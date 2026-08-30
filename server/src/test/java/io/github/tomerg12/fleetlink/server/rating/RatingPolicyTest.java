package io.github.tomerg12.fleetlink.server.rating;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Locks decisive registered eligibility and every guest or NO_CONTEST exclusion.
 */
class RatingPolicyTest {

    /**
     * Rates all four locked decisive reasons for two registered participants.
     */
    @Test
    void ratesEveryDecisiveRegisteredReason() {
        PlayerView first = player("First", false);
        PlayerView second = player("Second", false);

        assertTrue(RatingPolicy.isRated(GameEndReason.ALL_SHIPS_SUNK, first, second));
        assertTrue(RatingPolicy.isRated(GameEndReason.RESIGNATION, first, second));
        assertTrue(RatingPolicy.isRated(GameEndReason.DISCONNECT, first, second));
        assertTrue(RatingPolicy.isRated(GameEndReason.TIMEOUT, first, second));
    }

    /**
     * Excludes winnerless and every guest participation shape.
     */
    @Test
    void excludesNoContestAndGuestParticipation() {
        PlayerView registered = player("Registered", false);
        PlayerView guest = player("Guest", true);

        assertFalse(RatingPolicy.isRated(GameEndReason.NO_CONTEST,
                registered, player("Other", false)));
        assertFalse(RatingPolicy.isRated(GameEndReason.RESIGNATION, registered, guest));
        assertFalse(RatingPolicy.isRated(GameEndReason.DISCONNECT, guest, registered));
        assertFalse(RatingPolicy.isRated(GameEndReason.TIMEOUT, guest, player("GuestTwo", true)));
    }

    /**
     * Creates a safe participant for policy tests.
     *
     * @param name display name
     * @param guest guest flag
     * @return participant view with the fixed initial rating
     */
    private static PlayerView player(String name, boolean guest) {
        return new PlayerView(UUID.randomUUID(), name, 1000, guest);
    }
}
