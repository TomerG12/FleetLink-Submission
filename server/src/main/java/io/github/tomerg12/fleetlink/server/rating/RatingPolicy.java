package io.github.tomerg12.fleetlink.server.rating;

import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Defines which authoritative terminal games are eligible for registered Elo transitions.
 */
public final class RatingPolicy {
    private static final EnumSet<GameEndReason> RATED_REASONS = EnumSet.of(
            GameEndReason.ALL_SHIPS_SUNK,
            GameEndReason.RESIGNATION,
            GameEndReason.DISCONNECT,
            GameEndReason.TIMEOUT);

    /**
     * Prevents construction because eligibility is stateless.
     */
    private RatingPolicy() {
    }

    /**
     * Checks whether both participants are registered and the terminal reason is decisive.
     *
     * @param reason authoritative terminal reason
     * @param first first game participant
     * @param second second game participant
     * @return true only for registered-versus-registered decisive games
     */
    public static boolean isRated(GameEndReason reason, PlayerView first, PlayerView second) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return !first.isGuest() && !second.isGuest() && RATED_REASONS.contains(reason);
    }
}
