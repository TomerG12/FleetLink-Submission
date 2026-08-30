package io.github.tomerg12.fleetlink.server.rmi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.rematch.RematchCoordinator;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies T6.2 validation order and stable availability failures in the persistence-free core.
 */
class StatisticsRemoteValidationTest {

    /**
     * Resolves session before bounds, bounds before guest policy, and service availability last.
     *
     * @throws Exception if an in-process remote-compatible call fails unexpectedly
     */
    @Test
    void appliesLockedValidationOrderWithoutPersistence() throws Exception {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        SessionRegistry sessions = new SessionRegistry();
        MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessions.findSession(sessionId)
                        .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                        .orElse(false));
        RematchCoordinator rematches = new RematchCoordinator(
                sessions, games, matchmaking, callbacks);
        FleetLinkServerRemoteImpl server = new FleetLinkServerRemoteImpl(
                sessions, callbacks, matchmaking, coordinator, rematches);
        try {
            UUID invalidSession = UUID.randomUUID();
            assertEquals(ResultCode.INVALID_SESSION,
                    server.getPlayerStatistics(invalidSession, -1, 0).getResultCode());
            assertEquals(ResultCode.INVALID_SESSION,
                    server.getLeaderboard(invalidSession, 0).getResultCode());

            SessionInfo guest = server.connectAsGuest("Guest", new NoOpCallback())
                    .getSessionInfo();
            assertEquals(ResultCode.INVALID_REQUEST,
                    server.getPlayerStatistics(guest.getSessionId(), -1, 1).getResultCode());
            assertEquals(ResultCode.REGISTERED_ACCOUNT_REQUIRED,
                    server.getPlayerStatistics(guest.getSessionId(), 0, 1).getResultCode());
            assertEquals(ResultCode.INVALID_REQUEST,
                    server.getLeaderboard(guest.getSessionId(), 1).getResultCode());

            SessionInfo registered = sessions.claimRegistered(new PlayerView(
                    UUID.randomUUID(), "Account", 1000, false)).orElseThrow();
            assertEquals(ResultCode.INVALID_REQUEST,
                    server.getPlayerStatistics(registered.getSessionId(), 0, 1).getResultCode());
            assertEquals(ResultCode.INVALID_REQUEST,
                    server.getLeaderboard(registered.getSessionId(), 1).getResultCode());
        } finally {
            coordinator.close();
        }
    }

    /**
     * Supplies the callback requirement without retaining presentation state.
     */
    private static final class NoOpCallback implements FleetLinkClientCallback {
        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) {
        }
    }
}
