package io.github.tomerg12.fleetlink.server.matchmaking;

import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Performs atomic in-memory matchmaking and owns the common synchronized creation boundary used by
 * ordinary matches and rematches. Exact session authority, waiting indexes, source-game identity,
 * and mandatory first-FIFO activation admission are finalized while holding one matchmaking lock.
 * Callback and activation execution occur only after that lock is released.
 */
public final class MatchmakingService {
    private static final int DEFAULT_MAX_RATING_DIFFERENCE = 200;

    private final Object lock = new Object();
    private final ConcurrentSkipListMap<Integer, Deque<WaitingPlayer>> waitingByRating =
            new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<UUID, WaitingPlayer> waitingByPlayerId =
            new ConcurrentHashMap<>();
    private final GameSessionManager gameSessionManager;
    private final ClientCallbackRegistry callbackRegistry;
    private final GameCoordinator gameCoordinator;
    private final BooleanSupplier firstPlayerStarts;
    private final int maxRatingDifference;
    private final RegisteredRatingRegistry ratingRegistry;
    private final SessionValidityGuard sessionValidityGuard;
    private long waitingSequence;

    /**
     * Creates matchmaking with production first-player selection and exact session validation.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry connected callback registry
     * @param gameCoordinator authoritative game coordinator
     * @param ratingRegistry process-live registered rating authority
     * @param sessionValidityGuard exact session authority backed by the process session registry
     */
    public MatchmakingService(GameSessionManager gameSessionManager,
                              ClientCallbackRegistry callbackRegistry,
                              GameCoordinator gameCoordinator,
                              RegisteredRatingRegistry ratingRegistry,
                              SessionValidityGuard sessionValidityGuard) {
        this(gameSessionManager, callbackRegistry, gameCoordinator, ratingRegistry,
                sessionValidityGuard, () -> ThreadLocalRandom.current().nextBoolean(),
                DEFAULT_MAX_RATING_DIFFERENCE);
    }

    /**
     * Creates matchmaking with an internally owned coordinator and exact session validation.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry connected callback registry
     * @param ratingRegistry process-live registered rating authority
     * @param sessionValidityGuard exact session authority backed by the process session registry
     */
    public MatchmakingService(GameSessionManager gameSessionManager,
                              ClientCallbackRegistry callbackRegistry,
                              RegisteredRatingRegistry ratingRegistry,
                              SessionValidityGuard sessionValidityGuard) {
        this(gameSessionManager, callbackRegistry,
                new GameCoordinator(gameSessionManager, callbackRegistry, ratingRegistry),
                ratingRegistry, sessionValidityGuard);
    }

    /**
     * Creates deterministic package-local matchmaking with a permissive fixture session guard.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param ratingRegistry process-live registered rating authority
     * @param firstPlayerStarts deterministic starter selector
     * @param maxRatingDifference largest eligible immediate rating gap
     */
    MatchmakingService(GameSessionManager gameSessionManager,
                       ClientCallbackRegistry callbackRegistry,
                       RegisteredRatingRegistry ratingRegistry,
                       BooleanSupplier firstPlayerStarts, int maxRatingDifference) {
        this(gameSessionManager, callbackRegistry,
                new GameCoordinator(gameSessionManager, callbackRegistry, ratingRegistry),
                ratingRegistry, (sessionId, playerId) -> true,
                firstPlayerStarts, maxRatingDifference);
    }

    /**
     * Creates fully injectable package-local matchmaking with a permissive fixture session guard.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param gameCoordinator authoritative game coordinator
     * @param ratingRegistry process-live registered rating authority
     * @param firstPlayerStarts deterministic starter selector
     * @param maxRatingDifference largest eligible immediate rating gap
     */
    MatchmakingService(GameSessionManager gameSessionManager,
                       ClientCallbackRegistry callbackRegistry,
                       GameCoordinator gameCoordinator,
                       RegisteredRatingRegistry ratingRegistry,
                       BooleanSupplier firstPlayerStarts, int maxRatingDifference) {
        this(gameSessionManager, callbackRegistry, gameCoordinator, ratingRegistry,
                (sessionId, playerId) -> true, firstPlayerStarts, maxRatingDifference);
    }

    /**
     * Creates fully injectable matchmaking.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param gameCoordinator authoritative game coordinator
     * @param ratingRegistry process-live registered rating authority
     * @param sessionValidityGuard exact session authority
     * @param firstPlayerStarts deterministic starter selector
     * @param maxRatingDifference largest eligible immediate rating gap
     */
    MatchmakingService(GameSessionManager gameSessionManager,
                       ClientCallbackRegistry callbackRegistry,
                       GameCoordinator gameCoordinator,
                       RegisteredRatingRegistry ratingRegistry,
                       SessionValidityGuard sessionValidityGuard,
                       BooleanSupplier firstPlayerStarts, int maxRatingDifference) {
        this.gameSessionManager = Objects.requireNonNull(gameSessionManager, "gameSessionManager");
        this.callbackRegistry = Objects.requireNonNull(callbackRegistry, "callbackRegistry");
        this.gameCoordinator = Objects.requireNonNull(gameCoordinator, "gameCoordinator");
        this.ratingRegistry = Objects.requireNonNull(ratingRegistry, "ratingRegistry");
        this.sessionValidityGuard = Objects.requireNonNull(
                sessionValidityGuard, "sessionValidityGuard");
        this.firstPlayerStarts = Objects.requireNonNull(firstPlayerStarts, "firstPlayerStarts");
        if (maxRatingDifference < 0) {
            throw new IllegalArgumentException("max rating difference must not be negative");
        }
        this.maxRatingDifference = maxRatingDifference;
    }

    /**
     * Atomically revalidates exact session authority, then queues a player or creates and prepares
     * one normal game. A previously resolved player view cannot authorize a later WAITING or
     * MATCHED commit after its exact session is invalidated.
     *
     * @param sessionId exact session captured by the remote request
     * @param player safe player view initially resolved from that session
     * @return WAITING, MATCHED, INVALID_SESSION, or another expected state failure
     */
    public MatchmakingResult join(UUID sessionId, PlayerView player) {
        Objects.requireNonNull(player, "player");
        MatchCreated created = null;
        synchronized (lock) {
            if (!sessionValidityGuard.isCurrent(sessionId, player.getPlayerId())) {
                return MatchmakingResult.failure(ResultCode.INVALID_SESSION, "Invalid session");
            }
            if (callbackRegistry.find(player.getPlayerId()).isEmpty()) {
                return MatchmakingResult.failure(
                        ResultCode.INVALID_REQUEST, "Player has no registered callback");
            }
            if (gameSessionManager.hasActiveGame(player.getPlayerId())) {
                return MatchmakingResult.failure(
                        ResultCode.INVALID_REQUEST, "Player is already in an active game");
            }
            if (waitingByPlayerId.containsKey(player.getPlayerId())) {
                return MatchmakingResult.failure(
                        ResultCode.ALREADY_WAITING, "Player is already waiting for a match");
            }
            PlayerView authoritativePlayer = ratingRegistry.authoritativeView(player);
            WaitingPlayer candidate = selectNearestValidCandidate(authoritativePlayer.getRating());
            if (!sessionValidityGuard.isCurrent(sessionId, player.getPlayerId())) {
                return MatchmakingResult.failure(ResultCode.INVALID_SESSION, "Invalid session");
            }
            if (candidate == null) {
                WaitingPlayer waiting = new WaitingPlayer(sessionId, authoritativePlayer,
                        authoritativePlayer.getRating(), waitingSequence++);
                waitingByPlayerId.put(authoritativePlayer.getPlayerId(), waiting);
                waitingByRating.computeIfAbsent(authoritativePlayer.getRating(),
                        ignored -> new ArrayDeque<>()).addLast(waiting);
                return MatchmakingResult.success(MatchmakingState.WAITING);
            }
            removeWaiting(candidate);
            created = createPreparedGame(candidate.player(), authoritativePlayer);
        }
        created.activation().releaseAndAwait();
        return MatchmakingResult.success(MatchmakingState.MATCHED);
    }

    /**
     * Performs final rematch eligibility and common game creation under the ordinary matchmaking
     * lock.
     *
     * @param completedGameId exact completed source game
     * @param playerOneSessionId exact first participant session captured by negotiation
     * @param playerOne first source participant view
     * @param playerTwoSessionId exact second participant session captured by negotiation
     * @param playerTwo second source participant view
     * @return SUCCESS after one new game activates, otherwise REMATCH_NOT_AVAILABLE
     */
    public OperationResult createRematch(UUID completedGameId,
                                         UUID playerOneSessionId, PlayerView playerOne,
                                         UUID playerTwoSessionId, PlayerView playerTwo) {
        Objects.requireNonNull(completedGameId, "completedGameId");
        Objects.requireNonNull(playerOne, "playerOne");
        Objects.requireNonNull(playerTwo, "playerTwo");
        MatchCreated created;
        synchronized (lock) {
            if (!isRematchEligible(completedGameId, playerOneSessionId, playerOne,
                    playerTwoSessionId, playerTwo)) {
                return rematchUnavailable();
            }
            PlayerView authoritativeOne = ratingRegistry.authoritativeView(playerOne);
            PlayerView authoritativeTwo = ratingRegistry.authoritativeView(playerTwo);
            if (!isRematchEligible(completedGameId, playerOneSessionId, authoritativeOne,
                    playerTwoSessionId, authoritativeTwo)) {
                return rematchUnavailable();
            }
            created = createPreparedGame(authoritativeOne, authoritativeTwo);
        }
        created.activation().releaseAndAwait();
        return OperationResult.success();
    }

    /**
     * Removes one player from waiting state under the matchmaking lock.
     *
     * @param playerId waiting player identifier
     * @return success or NOT_WAITING
     */
    public OperationResult cancel(UUID playerId) {
        synchronized (lock) {
            WaitingPlayer waiting = waitingByPlayerId.get(playerId);
            if (waiting == null) {
                return OperationResult.failure(ResultCode.NOT_WAITING, "Player is not waiting");
            }
            removeWaiting(waiting);
            return OperationResult.success();
        }
    }

    /**
     * Linearizes exact-session termination against joins and rematch creation and removes only the
     * waiting entry owned by that exact ending session.
     *
     * @param sessionId exact invalidated session
     * @param playerId player owned by the invalidated session
     */
    public void terminateSession(UUID sessionId, UUID playerId) {
        synchronized (lock) {
            WaitingPlayer waiting = waitingByPlayerId.get(playerId);
            if (waiting != null && waiting.sessionId().equals(sessionId)) {
                removeWaiting(waiting);
            }
        }
    }

    /**
     * Checks exact waiting membership.
     *
     * @param playerId player identifier
     * @return true when a waiting entry exists
     */
    public boolean isWaiting(UUID playerId) {
        return playerId != null && waitingByPlayerId.containsKey(playerId);
    }

    /**
     * Repeatedly selects the nearest candidate and prunes stale exact-session entries before use.
     *
     * @param rating joining player's rating
     * @return nearest valid waiting candidate, or null
     */
    private WaitingPlayer selectNearestValidCandidate(int rating) {
        while (true) {
            WaitingPlayer candidate = selectNearestCandidate(rating);
            if (candidate == null) {
                return null;
            }
            if (isCurrentWaitingCandidate(candidate)) {
                return candidate;
            }
            removeWaiting(candidate);
        }
    }

    /**
     * Reports whether one waiting entry still has exact session, callback, and game availability.
     *
     * @param candidate waiting entry selected from an ordered bucket
     * @return true when the entry may be consumed
     */
    private boolean isCurrentWaitingCandidate(WaitingPlayer candidate) {
        UUID playerId = candidate.player().getPlayerId();
        return waitingByPlayerId.get(playerId) == candidate
                && sessionValidityGuard.isCurrent(candidate.sessionId(), playerId)
                && callbackRegistry.find(playerId).isPresent()
                && !gameSessionManager.hasActiveGame(playerId);
    }

    /**
     * Selects the nearest bucket head while preserving rating and FIFO policy.
     *
     * @param rating joining player's rating
     * @return selected bucket head, or null when none is in range
     */
    private WaitingPlayer selectNearestCandidate(int rating) {
        Map.Entry<Integer, Deque<WaitingPlayer>> lower = waitingByRating.floorEntry(rating);
        Map.Entry<Integer, Deque<WaitingPlayer>> higher = waitingByRating.ceilingEntry(rating);
        if (lower == null && higher == null) {
            return null;
        }
        if (lower != null && higher != null && lower.getKey().equals(higher.getKey())) {
            return lower.getValue().peekFirst();
        }
        if (lower == null) {
            return distance(higher.getKey(), rating) <= maxRatingDifference
                    ? higher.getValue().peekFirst() : null;
        }
        if (higher == null) {
            return distance(rating, lower.getKey()) <= maxRatingDifference
                    ? lower.getValue().peekFirst() : null;
        }
        long lowerDistance = distance(rating, lower.getKey());
        long higherDistance = distance(higher.getKey(), rating);
        WaitingPlayer candidate;
        long candidateDistance;
        if (lowerDistance < higherDistance) {
            candidate = lower.getValue().peekFirst();
            candidateDistance = lowerDistance;
        } else if (higherDistance < lowerDistance) {
            candidate = higher.getValue().peekFirst();
            candidateDistance = higherDistance;
        } else {
            WaitingPlayer lowerPlayer = lower.getValue().peekFirst();
            WaitingPlayer higherPlayer = higher.getValue().peekFirst();
            candidate = lowerPlayer.sequence() <= higherPlayer.sequence()
                    ? lowerPlayer : higherPlayer;
            candidateDistance = lowerDistance;
        }
        return candidateDistance <= maxRatingDifference ? candidate : null;
    }

    /**
     * Computes overflow-safe absolute rating distance.
     *
     * @param first first rating
     * @param second second rating
     * @return absolute distance as a long
     */
    private static long distance(int first, int second) {
        return Math.abs((long) first - second);
    }

    /**
     * Validates exact rematch sessions, callbacks, queue state, and completed source mappings.
     *
     * @param completedGameId expected completed source
     * @param playerOneSessionId first exact session
     * @param playerOne first participant
     * @param playerTwoSessionId second exact session
     * @param playerTwo second participant
     * @return true when common game creation may proceed
     */
    private boolean isRematchEligible(UUID completedGameId,
                                      UUID playerOneSessionId, PlayerView playerOne,
                                      UUID playerTwoSessionId, PlayerView playerTwo) {
        UUID firstId = playerOne.getPlayerId();
        UUID secondId = playerTwo.getPlayerId();
        if (firstId.equals(secondId)
                || !sessionValidityGuard.isCurrent(playerOneSessionId, firstId)
                || !sessionValidityGuard.isCurrent(playerTwoSessionId, secondId)
                || callbackRegistry.find(firstId).isEmpty()
                || callbackRegistry.find(secondId).isEmpty()
                || waitingByPlayerId.containsKey(firstId)
                || waitingByPlayerId.containsKey(secondId)
                || gameSessionManager.hasActiveGame(firstId)
                || gameSessionManager.hasActiveGame(secondId)) {
            return false;
        }
        GameSession source = gameSessionManager.findByGameId(completedGameId).orElse(null);
        if (source == null || !source.isFinished() || !source.containsPlayer(firstId)
                || !source.containsPlayer(secondId)) {
            return false;
        }
        return gameSessionManager.findByPlayerId(firstId).orElse(null) == source
                && gameSessionManager.findByPlayerId(secondId).orElse(null) == source;
    }

    /**
     * Creates one indexed pre-activation game and admits activation first while the matchmaking lock
     * is held.
     *
     * @param first first participant using authoritative live rating
     * @param second second participant using authoritative live rating
     * @return created game and prepared activation
     */
    private MatchCreated createPreparedGame(PlayerView first, PlayerView second) {
        GameCoordinator.PreparedActivation activation =
                gameCoordinator.prepareMatchedGameActivation();
        boolean admitted = false;
        try {
            UUID startingPlayerId = firstPlayerStarts.getAsBoolean()
                    ? first.getPlayerId() : second.getPlayerId();
            GameSession game = gameSessionManager.createGame(first, second, startingPlayerId);
            activation.admit(game);
            admitted = true;
            return new MatchCreated(game, activation);
        } finally {
            if (!admitted) {
                activation.close();
            }
        }
    }

    /**
     * Removes one waiting entry consistently from both indexes.
     *
     * @param waiting waiting entry to remove
     */
    private void removeWaiting(WaitingPlayer waiting) {
        waitingByPlayerId.remove(waiting.player().getPlayerId(), waiting);
        Deque<WaitingPlayer> bucket = waitingByRating.get(waiting.matchmakingRating());
        if (bucket == null) {
            return;
        }
        bucket.remove(waiting);
        if (bucket.isEmpty()) {
            waitingByRating.remove(waiting.matchmakingRating(), bucket);
        }
    }

    /**
     * Creates the standard final rematch rejection.
     *
     * @return REMATCH_NOT_AVAILABLE result
     */
    private static OperationResult rematchUnavailable() {
        return OperationResult.failure(
                ResultCode.REMATCH_NOT_AVAILABLE, "Rematch is no longer available");
    }

    /** Validates that an exact session still belongs to the expected player. */
    @FunctionalInterface
    public interface SessionValidityGuard {
        /**
         * Checks current exact session ownership.
         *
         * @param sessionId exact session identifier
         * @param playerId expected player identifier
         * @return true only while the exact session remains active for that player
         */
        boolean isCurrent(UUID sessionId, UUID playerId);
    }

    /**
     * Stores exact session authority, live player state, and deterministic queue metadata.
     *
     * @param sessionId exact captured session
     * @param player authoritative player view for the waiting entry
     * @param matchmakingRating rating bucket key
     * @param sequence global waiting order
     */
    private record WaitingPlayer(UUID sessionId, PlayerView player,
                                 int matchmakingRating, long sequence) {
    }

    /**
     * Carries a new indexed game and its already admitted activation outside the matchmaking lock.
     *
     * @param game newly indexed game
     * @param activation gated first-FIFO activation
     */
    private record MatchCreated(GameSession game,
                                GameCoordinator.PreparedActivation activation) {
    }
}
