package io.github.tomerg12.fleetlink.server.service;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.player;
import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.rmi.RemoteException;
import org.junit.jupiter.api.Test;

/**
 * Verifies post-commit game-state callbacks and disconnect coordination after explicit placement
 * activation.
 */
class GameCoordinatorTest {

    /**
     * Notifies both players when battle starts and after an accepted shot.
     */
    @Test
    void pushesCommittedBattleStateAfterActions() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        try {
            PlayerView first = player("First", 1200);
            PlayerView second = player("Second", 1200);
            seed(ratings, first, second);
            RecordingCallback firstCallback = new RecordingCallback();
            RecordingCallback secondCallback = new RecordingCallback();
            callbacks.register(first, firstCallback);
            callbacks.register(second, secondCallback);
            GameSession game = games.createGame(first, second, first.getPlayerId());
            coordinator.activateMatchedGame(game);

            coordinator.submitFleet(first.getPlayerId(), validFleet());
            assertEquals(0, firstCallback.gameStateCount);
            assertEquals(0, secondCallback.gameStateCount);

            coordinator.submitFleet(second.getPlayerId(), validFleet());
            assertEquals(1, firstCallback.gameStateCount);
            assertEquals(1, secondCallback.gameStateCount);
            assertEquals(GamePhase.BATTLE, firstCallback.lastGameView.getPhase());

            coordinator.fire(first.getPlayerId(), new Coordinate(9, 9));
            assertEquals(2, firstCallback.gameStateCount);
            assertEquals(2, secondCallback.gameStateCount);
        } finally {
            coordinator.close();
        }
    }

    /**
     * Removes the disconnected callback and sends a final disconnect snapshot to the opponent.
     */
    @Test
    void disconnectNotifiesOnlyRemainingOpponent() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        try {
            PlayerView first = player("First", 1200);
            PlayerView second = player("Second", 1200);
            seed(ratings, first, second);
            RecordingCallback firstCallback = new RecordingCallback();
            RecordingCallback secondCallback = new RecordingCallback();
            callbacks.register(first, firstCallback);
            callbacks.register(second, secondCallback);
            GameSession game = games.createGame(first, second, first.getPlayerId());
            coordinator.activateMatchedGame(game);
            assertTrue(coordinator.disconnect(second.getPlayerId()).isSuccess());
            assertEquals(1, firstCallback.gameStateCount);
            assertEquals(0, secondCallback.gameStateCount);
            assertEquals(GamePhase.FINISHED, firstCallback.lastGameView.getPhase());
            assertEquals(GameEndReason.DISCONNECT, firstCallback.lastGameView.getEndReason());
            assertEquals(first.getPlayerId(), firstCallback.lastGameView.getWinner().getPlayerId());
            assertEquals(1216, ratings.current(first.getPlayerId()).getRating());
            assertEquals(1184, ratings.current(second.getPlayerId()).getRating());
            assertTrue(callbacks.find(second.getPlayerId()).isEmpty());
        } finally {
            coordinator.close();
        }
    }

    /**
     * Seeds both registered participants in the process-live rating authority.
     *
     * @param ratings process-live rating registry
     * @param first first registered participant
     * @param second second registered participant
     */
    private static void seed(RegisteredRatingRegistry ratings,
                             PlayerView first, PlayerView second) {
        ratings.seedIfAbsent(first.getPlayerId(), first.getRating(), 0L);
        ratings.seedIfAbsent(second.getPlayerId(), second.getRating(), 0L);
    }

    /**
     * Records the most recent game-state callback for assertions.
     */
    private static final class RecordingCallback implements FleetLinkClientCallback {
        private int gameStateCount;
        private GameView lastGameView;

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            gameStateCount++;
            lastGameView = gameView;
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) throws RemoteException {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) throws RemoteException {
        }
    }
}
