package io.github.tomerg12.fleetlink.server.game;

import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Indexes in-memory game sessions for direct lookup by game and participant identifiers.
 * Compound create and remove transitions are synchronized to keep both indexes consistent.
 */
public final class GameSessionManager {

    private final ConcurrentHashMap<UUID, GameSession> gamesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> gameByPlayerId = new ConcurrentHashMap<>();
    private final Set<UUID> terminalFinalizedGameIds = ConcurrentHashMap.newKeySet();
    private final Supplier<UUID> gameIdSupplier;

    /**
     * Creates a manager that generates random UUID game identifiers.
     */
    public GameSessionManager() {
        this(UUID::randomUUID);
    }

    /**
     * Creates a manager with an injectable game identifier source for deterministic tests.
     *
     * @param gameIdSupplier the source used when a new game is created
     */
    GameSessionManager(Supplier<UUID> gameIdSupplier) {
        this.gameIdSupplier = Objects.requireNonNull(gameIdSupplier, "gameIdSupplier");
    }

    /**
     * Creates and indexes exactly one game for two currently available participants.
     * A finished previous game continues to reserve both participants until its coordinator has
     * published completion of authoritative terminal finalization.
     *
     * @param playerOne the first participant
     * @param playerTwo the second participant
     * @param startingPlayerId the participant who receives the first battle turn
     * @return the newly created game session
     * @throws IllegalArgumentException if either participant is already in an unfinished game
     */
    public synchronized GameSession createGame(PlayerView playerOne, PlayerView playerTwo,
                                               UUID startingPlayerId) {
        Objects.requireNonNull(playerOne, "playerOne");
        Objects.requireNonNull(playerTwo, "playerTwo");
        clearFinalizedMapping(playerOne.getPlayerId());
        clearFinalizedMapping(playerTwo.getPlayerId());
        if (hasActiveGame(playerOne.getPlayerId()) || hasActiveGame(playerTwo.getPlayerId())) {
            throw new IllegalArgumentException("participant is already in an active game");
        }

        UUID gameId = nextUniqueGameId();
        GameSession session = new GameSession(gameId, playerOne, playerTwo, startingPlayerId);
        gamesById.put(gameId, session);
        gameByPlayerId.put(playerOne.getPlayerId(), gameId);
        gameByPlayerId.put(playerTwo.getPlayerId(), gameId);
        return session;
    }

    /**
     * Finds a game by its exact identifier.
     *
     * @param gameId the game identifier
     * @return the matching session when present
     */
    public Optional<GameSession> findByGameId(UUID gameId) {
        return Optional.ofNullable(gameId == null ? null : gamesById.get(gameId));
    }

    /**
     * Finds the most recently indexed game for one player.
     * The returned session may already be finished until a newer game replaces the player mapping.
     *
     * @param playerId the player identifier
     * @return the indexed session when present
     */
    public Optional<GameSession> findByPlayerId(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        UUID gameId = gameByPlayerId.get(playerId);
        return gameId == null ? Optional.empty() : findByGameId(gameId);
    }

    /**
     * Reports whether a player is unavailable for a new game because an indexed game is unfinished
     * or its finished terminal transition has not yet completed authoritative finalization.
     *
     * @param playerId the player identifier
     * @return true while an unfinished or terminal-finalizing game reserves the player
     */
    public boolean hasActiveGame(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        UUID gameId = gameByPlayerId.get(playerId);
        if (gameId == null) {
            return false;
        }
        GameSession session = gamesById.get(gameId);
        return session != null
                && (!session.isFinished() || !terminalFinalizedGameIds.contains(gameId));
    }

    /**
     * Publishes that terminal domain facts, any live rating transition, and immutable completion
     * mapping are finalized for a finished game. The concurrent publication is the availability
     * barrier read by matchmaking before it captures a participant's current live rating.
     *
     * @param gameId finished game whose participants may become available
     * @throws NullPointerException if gameId is null
     * @throws IllegalArgumentException if the game is not indexed
     * @throws IllegalStateException if the game has not reached terminal domain state
     */
    public synchronized void markTerminalFinalizationComplete(UUID gameId) {
        Objects.requireNonNull(gameId, "gameId");
        GameSession session = gamesById.get(gameId);
        if (session == null) {
            throw new IllegalArgumentException("game is not indexed");
        }
        if (!session.isFinished()) {
            throw new IllegalStateException("game is not finished");
        }
        terminalFinalizedGameIds.add(gameId);
    }

    /**
     * Removes one finalized finished game and both participant indexes.
     * Unfinished and terminal-finalizing games cannot be removed because either removal would
     * prematurely make participants available to matchmaking.
     *
     * @param gameId the game identifier to remove
     * @return true when an indexed game was removed
     */
    public synchronized boolean removeGame(UUID gameId) {
        GameSession removed = gamesById.get(gameId);
        if (removed == null || !removed.isFinished()
                || !terminalFinalizedGameIds.contains(gameId)) {
            return false;
        }
        gamesById.remove(gameId);
        for (UUID playerId : removed.getParticipantIds()) {
            gameByPlayerId.remove(playerId, gameId);
        }
        terminalFinalizedGameIds.remove(gameId);
        return true;
    }

    /**
     * Removes a stale player-to-game mapping only after its indexed terminal finalization is
     * complete. This method is called while holding the manager monitor.
     *
     * @param playerId the player whose mapping may be stale
     */
    private void clearFinalizedMapping(UUID playerId) {
        UUID gameId = gameByPlayerId.get(playerId);
        if (gameId == null) {
            return;
        }
        GameSession session = gamesById.get(gameId);
        if (session == null
                || (session.isFinished() && terminalFinalizedGameIds.contains(gameId))) {
            gameByPlayerId.remove(playerId, gameId);
        }
    }

    /**
     * Generates a game identifier that is not already present in the game index.
     *
     * @return a unique game identifier
     */
    private UUID nextUniqueGameId() {
        UUID gameId;
        do {
            gameId = Objects.requireNonNull(gameIdSupplier.get(), "generated gameId");
        } while (gamesById.containsKey(gameId));
        return gameId;
    }
}
