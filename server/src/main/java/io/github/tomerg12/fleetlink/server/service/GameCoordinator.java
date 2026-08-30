package io.github.tomerg12.fleetlink.server.service;

import io.github.tomerg12.fleetlink.server.completion.CompletedGameSnapshot;
import io.github.tomerg12.fleetlink.server.completion.CompletedParticipantSnapshot;
import io.github.tomerg12.fleetlink.server.completion.CompletionSink;
import io.github.tomerg12.fleetlink.server.deadline.DeadlineScheduler;
import io.github.tomerg12.fleetlink.server.deadline.ScheduledExecutorDeadlineScheduler;
import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.game.ParticipantTelemetrySnapshot;
import io.github.tomerg12.fleetlink.server.game.TerminalGameSnapshot;
import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.server.rating.PlayerRatingAdjustment;
import io.github.tomerg12.fleetlink.server.rating.RatedGameAdjustment;
import io.github.tomerg12.fleetlink.server.rating.RatingPolicy;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.GameViewResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.rmi.RemoteException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates authoritative game commands, D-013 delivery ordering, deadlines, and completion
 * handoff. Per-game admission happens before D-013 so an admitted on-time command cannot be
 * overtaken by a later expiry notification because of thread scheduling.
 */
public final class GameCoordinator implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(GameCoordinator.class.getName());
    private static final Duration PLACEMENT_WINDOW = Duration.ofSeconds(120);
    private static final Duration BATTLE_TURN_WINDOW = Duration.ofSeconds(45);

    private final GameSessionManager gameSessionManager;
    private final ClientCallbackRegistry callbackRegistry;
    private final CompletionSink completionSink;
    private final Clock clock;
    private final DeadlineScheduler deadlineScheduler;
    private final boolean ownsDeadlineScheduler;
    private final GameCommandSequencer sequencer;
    private final RegisteredRatingRegistry ratingRegistry;
    private final Runnable terminalMutationObserver;
    private final ConcurrentHashMap<UUID, ReentrantLock> deliveryLanes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, DeadlineScheduler.Handle> deadlineHandles =
            new ConcurrentHashMap<>();

    /**
     * Creates a coordinator with in-memory completion handling and production deadline resources.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @throws NullPointerException if a required dependency is null
     */
    public GameCoordinator(GameSessionManager gameSessionManager,
                           ClientCallbackRegistry callbackRegistry) {
        this(gameSessionManager, callbackRegistry, ignored -> { }, Clock.systemUTC());
    }

    /**
     * Creates a coordinator with in-memory completion and an explicit live rating authority.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param ratingRegistry process-live registered rating authority
     */
    public GameCoordinator(GameSessionManager gameSessionManager,
                           ClientCallbackRegistry callbackRegistry,
                           RegisteredRatingRegistry ratingRegistry) {
        this(gameSessionManager, callbackRegistry, ignored -> { }, Clock.systemUTC(),
                ratingRegistry);
    }

    /**
     * Creates a coordinator with completion handoff and production deadline resources.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param completionSink off-lane completion handoff
     * @param clock authoritative server clock
     * @throws NullPointerException if a required dependency is null
     */
    public GameCoordinator(GameSessionManager gameSessionManager,
                           ClientCallbackRegistry callbackRegistry,
                           CompletionSink completionSink, Clock clock) {
        this(gameSessionManager, callbackRegistry, completionSink, clock,
                new ScheduledExecutorDeadlineScheduler(clock), true,
                new RegisteredRatingRegistry(), () -> { });
    }

    /**
     * Creates a coordinator with production deadlines and an explicit live rating authority.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param completionSink off-lane completion handoff
     * @param clock authoritative server clock
     * @param ratingRegistry process-live registered rating authority
     */
    public GameCoordinator(GameSessionManager gameSessionManager,
                           ClientCallbackRegistry callbackRegistry,
                           CompletionSink completionSink, Clock clock,
                           RegisteredRatingRegistry ratingRegistry) {
        this(gameSessionManager, callbackRegistry, completionSink, clock,
                new ScheduledExecutorDeadlineScheduler(clock), true, ratingRegistry, () -> { });
    }

    /**
     * Creates production deadline coordination with a deterministic terminal-mutation observation
     * seam for concurrency tests. The observer runs under D-013 after terminal domain capture and
     * before live rating finalization.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param completionSink off-lane completion handoff
     * @param clock authoritative server clock
     * @param ratingRegistry process-live registered rating authority
     * @param terminalMutationObserver observer invoked in the terminal-finalization window
     */
    GameCoordinator(GameSessionManager gameSessionManager,
                    ClientCallbackRegistry callbackRegistry,
                    CompletionSink completionSink, Clock clock,
                    RegisteredRatingRegistry ratingRegistry,
                    Runnable terminalMutationObserver) {
        this(gameSessionManager, callbackRegistry, completionSink, clock,
                new ScheduledExecutorDeadlineScheduler(clock), true, ratingRegistry,
                terminalMutationObserver);
    }

    /**
     * Creates a coordinator with an injectable deadline scheduler for deterministic tests/runtime.
     * The coordinator owns the supplied scheduler lifecycle and closes it on {@link #close()}.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param completionSink off-lane completion handoff
     * @param clock authoritative server clock
     * @param deadlineScheduler gameplay deadline wake-up service
     * @throws NullPointerException if a required dependency is null
     */
    public GameCoordinator(GameSessionManager gameSessionManager,
                           ClientCallbackRegistry callbackRegistry,
                           CompletionSink completionSink, Clock clock,
                           DeadlineScheduler deadlineScheduler) {
        this(gameSessionManager, callbackRegistry, completionSink, clock, deadlineScheduler, true,
                new RegisteredRatingRegistry(), () -> { });
    }

    /**
     * Creates a coordinator with explicit completion, deadline, and live rating dependencies.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param completionSink off-lane completion handoff
     * @param clock authoritative server clock
     * @param deadlineScheduler gameplay deadline wake-up service
     * @param ratingRegistry process-live registered rating authority
     * @throws NullPointerException if a required dependency is null
     */
    public GameCoordinator(GameSessionManager gameSessionManager,
                           ClientCallbackRegistry callbackRegistry,
                           CompletionSink completionSink, Clock clock,
                           DeadlineScheduler deadlineScheduler,
                           RegisteredRatingRegistry ratingRegistry) {
        this(gameSessionManager, callbackRegistry, completionSink, clock, deadlineScheduler, true,
                ratingRegistry, () -> { });
    }

    /**
     * Creates fully wired coordination with explicit deadline-scheduler ownership.
     *
     * @param gameSessionManager authoritative game manager
     * @param callbackRegistry callback registry
     * @param completionSink off-lane completion handoff
     * @param clock authoritative server clock
     * @param deadlineScheduler gameplay deadline wake-up service
     * @param ownsDeadlineScheduler true when coordinator close must also close the scheduler
     * @param ratingRegistry process-live registered rating authority
     * @param terminalMutationObserver observer for deterministic terminal lifecycle tests
     * @throws NullPointerException if a required dependency is null
     */
    private GameCoordinator(GameSessionManager gameSessionManager,
                            ClientCallbackRegistry callbackRegistry,
                            CompletionSink completionSink, Clock clock,
                            DeadlineScheduler deadlineScheduler,
                            boolean ownsDeadlineScheduler,
                            RegisteredRatingRegistry ratingRegistry,
                            Runnable terminalMutationObserver) {
        this.gameSessionManager = Objects.requireNonNull(gameSessionManager, "gameSessionManager");
        this.callbackRegistry = Objects.requireNonNull(callbackRegistry, "callbackRegistry");
        this.completionSink = Objects.requireNonNull(completionSink, "completionSink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlineScheduler = Objects.requireNonNull(deadlineScheduler, "deadlineScheduler");
        this.ownsDeadlineScheduler = ownsDeadlineScheduler;
        this.ratingRegistry = Objects.requireNonNull(ratingRegistry, "ratingRegistry");
        this.terminalMutationObserver = Objects.requireNonNull(
                terminalMutationObserver, "terminalMutationObserver");
        this.sequencer = new GameCommandSequencer(clock);
    }

    /**
     * Activates a newly indexed match after the matchmaking lock has been released and delivers the
     * first authoritative placement snapshot concurrently to both participants.
     *
     * @param game newly indexed pre-activation game
     * @throws NullPointerException if game is null
     * @throws IllegalStateException if coordinator shutdown has stopped new admissions
     */
    public void activateMatchedGame(GameSession game) {
        Objects.requireNonNull(game, "game");
        sequencer.submit(game.getGameId(), ignored -> {
            executeActivation(game);
            return null;
        });
    }

    /**
     * Submits one complete fleet using the server ingress time captured during admission.
     *
     * @param playerId server-resolved participant
     * @param placements complete fleet request
     * @return authoritative fleet result
     */
    public FleetSubmissionResult submitFleet(UUID playerId, List<ShipPlacement> placements) {
        GameSession game = findGame(playerId);
        if (game == null) {
            return FleetSubmissionResult.rejected(
                    ResultCode.NOT_IN_GAME, "Player has no indexed game", null);
        }
        return sequencer.submit(game.getGameId(), receivedAt -> executeWithOrderedDelivery(game,
                () -> game.submitFleet(playerId, placements, receivedAt,
                        clock.instant().plus(BATTLE_TURN_WINDOW)),
                result -> result.isAccepted()
                        && result.getGameView().getPhase() == GamePhase.BATTLE,
                null));
    }

    /**
     * Applies one fire command using the server ingress time captured during admission.
     *
     * @param playerId server-resolved participant
     * @param coordinate target coordinate
     * @return authoritative shot result
     */
    public ShotResult fire(UUID playerId, Coordinate coordinate) {
        GameSession game = findGame(playerId);
        if (game == null) {
            return ShotResult.rejected(ResultCode.NOT_IN_GAME,
                    "Player has no indexed game", null);
        }
        return sequencer.submit(game.getGameId(), receivedAt -> executeWithOrderedDelivery(game,
                () -> game.fire(playerId, coordinate, receivedAt,
                        clock.instant().plus(BATTLE_TURN_WINDOW)),
                ShotResult::isAccepted, null));
    }

    /**
     * Returns the latest indexed player-specific snapshot. Pre-activation sessions explicitly fail
     * inside GameSession and therefore never expose a playable placement view with deadline zero.
     *
     * @param playerId server-resolved participant
     * @return current safe snapshot or an explicit failure
     */
    public GameViewResult getCurrentGame(UUID playerId) {
        GameSession game = findGame(playerId);
        if (game == null) {
            return GameViewResult.failure(ResultCode.NOT_IN_GAME, "Player has no indexed game");
        }
        return game.getCurrentGame(playerId);
    }

    /**
     * Ends an unfinished game as resignation through the same authoritative admission FIFO.
     *
     * @param playerId leaving participant
     * @return authoritative operation result
     */
    public OperationResult leaveGame(UUID playerId) {
        GameSession game = findGame(playerId);
        if (game == null) {
            return OperationResult.failure(ResultCode.NOT_IN_GAME, "Player has no indexed game");
        }
        return sequencer.submit(game.getGameId(), ignored -> executeWithOrderedDelivery(
                game, () -> game.leave(playerId), OperationResult::isSuccess, playerId));
    }

    /**
     * Ends an unfinished game as disconnect through the same authoritative admission FIFO.
     *
     * @param playerId disconnected participant
     * @return authoritative operation result
     */
    public OperationResult disconnect(UUID playerId) {
        GameSession game = findGame(playerId);
        if (game == null) {
            callbackRegistry.unregister(playerId);
            return OperationResult.failure(ResultCode.NOT_IN_GAME, "Player has no indexed game");
        }
        return sequencer.submit(game.getGameId(), ignored -> executeWithOrderedDelivery(game, () -> {
            OperationResult result = game.disconnect(playerId);
            callbackRegistry.unregister(playerId);
            return result;
        }, OperationResult::isSuccess, playerId));
    }

    /**
     * Commits placement activation and initial match-found delivery under one D-013 state boundary.
     *
     * @param game newly indexed pre-activation game
     */
    private void executeActivation(GameSession game) {
        ReentrantLock deliveryLane = deliveryLane(game.getGameId());
        deliveryLane.lock();
        try {
            Instant activatedAt = clock.instant();
            game.activatePlacement(activatedAt, activatedAt.plus(PLACEMENT_WINDOW));
            refreshDeadlineRegistration(game);
            notifyMatchFound(game);
        } finally {
            deliveryLane.unlock();
        }
    }

    /**
     * Executes one state-changing command inside D-013, preserving T5 terminal snapshot/completion
     * construction before callbacks and persistence handoff only after the lane unlocks.
     *
     * @param game authoritative game being mutated
     * @param action synchronized domain action to invoke while D-013 is held
     * @param shouldNotify predicate selecting ordinary callback-producing results
     * @param excludedPlayerId optional participant excluded from state callback delivery
     * @param <T> operation result type
     * @return authoritative operation result
     */
    private <T> T executeWithOrderedDelivery(GameSession game, Supplier<T> action,
                                             Predicate<T> shouldNotify,
                                             UUID excludedPlayerId) {
        ReentrantLock deliveryLane = deliveryLane(game.getGameId());
        T result;
        CompletedGameSnapshot completion = null;
        deliveryLane.lock();
        try {
            boolean wasFinished = game.isFinished();
            long priorGeneration = game.getDeadlineGeneration();
            result = action.get();
            long currentGeneration = game.getDeadlineGeneration();
            boolean deadlineChanged = priorGeneration != currentGeneration;
            if (deadlineChanged) {
                refreshDeadlineRegistration(game);
            }
            if (!wasFinished && game.isFinished()) {
                TerminalGameSnapshot terminal = game.captureTerminalState();
                terminalMutationObserver.run();
                if (terminal.getEndReason() != GameEndReason.NO_CONTEST) {
                    RatedGameAdjustment ratingAdjustment = applyLiveRatingIfEligible(terminal);
                    completion = toCompletionSnapshot(terminal, ratingAdjustment);
                }
                gameSessionManager.markTerminalFinalizationComplete(game.getGameId());
            }
            if (deadlineChanged || shouldNotify.test(result)) {
                notifyGameState(game, excludedPlayerId);
            }
        } finally {
            deliveryLane.unlock();
        }
        if (completion != null) {
            completionSink.submit(completion);
        }
        return result;
    }

    /**
     * Replaces the best-effort scheduled handle for the game's current deadline generation.
     * Cancellation is not relied on for correctness because generation validation rejects stale work.
     *
     * @param game authoritative game whose active deadline changed
     */
    private void refreshDeadlineRegistration(GameSession game) {
        UUID gameId = game.getGameId();
        DeadlineScheduler.Handle oldHandle = deadlineHandles.remove(gameId);
        if (oldHandle != null) {
            oldHandle.cancel();
        }
        Instant deadline = game.getActiveDeadline();
        if (deadline == null || game.isFinished()) {
            return;
        }
        long expectedGeneration = game.getDeadlineGeneration();
        DeadlineScheduler.Handle handle = deadlineScheduler.schedule(deadline,
                () -> sequencer.enqueue(gameId,
                        () -> executeDeadlineExpiry(gameId, expectedGeneration)));
        deadlineHandles.put(gameId, handle);
    }

    /**
     * Processes one already-admitted expiry command on a sequencer drain resource, never on the
     * deadline scheduler worker that originally enqueued it.
     *
     * @param gameId game whose scheduled deadline woke
     * @param expectedGeneration generation captured when that wake-up was registered
     */
    private void executeDeadlineExpiry(UUID gameId, long expectedGeneration) {
        GameSession game = gameSessionManager.findByGameId(gameId).orElse(null);
        if (game == null) {
            return;
        }
        executeWithOrderedDelivery(game, () -> {
            Instant now = clock.instant();
            return game.expireDeadline(expectedGeneration, now,
                    now.plus(BATTLE_TURN_WINDOW));
        }, Boolean::booleanValue, null);
    }

    /**
     * Maps winner-producing terminal state to the immutable persistence handoff while D-013 is held.
     * The completion timestamp is captured before callbacks, preserving the exact T5 completion order.
     *
     * @param terminal immutable terminal game-domain facts
     * @param ratingAdjustment atomic rated adjustment, or null for an unrated game
     * @return immutable completion snapshot for off-lane persistence handling
     * @throws NullPointerException if a winner-producing terminal state unexpectedly lacks a winner
     */
    private CompletedGameSnapshot toCompletionSnapshot(
            TerminalGameSnapshot terminal, RatedGameAdjustment ratingAdjustment) {
        UUID winnerPlayerId = Objects.requireNonNull(
                terminal.getWinnerPlayerId(), "winnerPlayerId");
        return new CompletedGameSnapshot(terminal.getGameId(), terminal.getStartedAt(),
                clock.instant(),
                terminal.getEndReason(), winnerPlayerId, List.of(
                        toParticipantSnapshot(terminal.getPlayerOne(),
                                terminal.getPlayerOneTelemetry(), winnerPlayerId, ratingAdjustment),
                        toParticipantSnapshot(terminal.getPlayerTwo(),
                                terminal.getPlayerTwoTelemetry(), winnerPlayerId,
                                ratingAdjustment)));
    }

    /**
     * Reserves mandatory sequencer admission before a matchmaking transaction indexes a new game.
     * The caller binds the created game while still holding matchmaking synchronization, then opens
     * the execution gate only after releasing that synchronization.
     *
     * @return prepared activation reservation
     * @throws IllegalStateException if coordinator shutdown has stopped new admissions
     */
    public PreparedActivation prepareMatchedGameActivation() {
        return new PreparedActivation(sequencer.reserveRequiredAdmission());
    }

    /**
     * Applies the process-live pair transition for an eligible registered decisive game.
     * This method performs only short in-memory work while D-013 is held.
     *
     * @param terminal immutable terminal game facts
     * @return rated adjustment, or null when either participant is a guest
     */
    private RatedGameAdjustment applyLiveRatingIfEligible(TerminalGameSnapshot terminal) {
        if (!RatingPolicy.isRated(terminal.getEndReason(), terminal.getPlayerOne(),
                terminal.getPlayerTwo())) {
            return null;
        }
        return ratingRegistry.applyRatedGame(terminal.getGameId(), terminal.getPlayerOne(),
                terminal.getPlayerTwo(), Objects.requireNonNull(terminal.getWinnerPlayerId(),
                        "winnerPlayerId"));
    }

    /**
     * Maps one terminal participant to the immutable WIN or LOSS completion value.
     *
     * @param player participant captured from terminal game-domain state
     * @param telemetry participant telemetry captured from the same terminal mutation
     * @param winnerPlayerId authoritative winner identifier
     * @param ratingAdjustment rated pair transition, or null for an unrated game
     * @return immutable participant completion snapshot
     */
    private static CompletedParticipantSnapshot toParticipantSnapshot(
            PlayerView player, ParticipantTelemetrySnapshot telemetry,
            UUID winnerPlayerId, RatedGameAdjustment ratingAdjustment) {
        ParticipantResult result = player.getPlayerId().equals(winnerPlayerId)
                ? ParticipantResult.WIN : ParticipantResult.LOSS;
        PlayerRatingAdjustment playerAdjustment = ratingAdjustment == null
                ? null : ratingAdjustment.adjustmentFor(player.getPlayerId());
        return new CompletedParticipantSnapshot(player.getPlayerId(), player.getDisplayName(),
                player.isGuest(), player.getRating(), result, telemetry.getShotsFired(),
                telemetry.getHits(), telemetry.getShipsSunk(), telemetry.getTurnsTaken(),
                playerAdjustment == null ? 0 : playerAdjustment.getRatingDelta(),
                playerAdjustment == null ? null : playerAdjustment.getRatingRevisionBefore());
    }

    /**
     * Delivers initial match-found snapshots concurrently after authoritative activation.
     *
     * @param game activated authoritative game
     */
    private void notifyMatchFound(GameSession game) {
        List<CallbackInvocation> invocations = new ArrayList<>();
        for (UUID participantId : game.getParticipantIds()) {
            FleetLinkClientCallback callback = callbackRegistry.find(participantId).orElse(null);
            if (callback == null) {
                continue;
            }
            GameViewResult view = game.getCurrentGame(participantId);
            if (view.isSuccess()) {
                invocations.add(new CallbackInvocation(participantId, callback,
                        view.getGameView(), true));
            }
        }
        invokeConcurrently(invocations);
    }

    /**
     * Delivers one committed state concurrently to eligible participants while D-013 is held.
     *
     * @param game authoritative game whose committed state is being delivered
     * @param excludedPlayerId optional participant that must not receive this state callback
     */
    private void notifyGameState(GameSession game, UUID excludedPlayerId) {
        List<CallbackInvocation> invocations = new ArrayList<>();
        for (UUID participantId : game.getParticipantIds()) {
            if (participantId.equals(excludedPlayerId)) {
                continue;
            }
            FleetLinkClientCallback callback = callbackRegistry.find(participantId).orElse(null);
            if (callback == null) {
                continue;
            }
            GameViewResult view = game.getCurrentGame(participantId);
            if (view.isSuccess()) {
                invocations.add(new CallbackInvocation(participantId, callback,
                        view.getGameView(), false));
            }
        }
        invokeConcurrently(invocations);
    }

    /**
     * Starts same-state participant callbacks on separate virtual threads and waits for every
     * attempt before allowing the next D-013 state mutation. A callback that never returns therefore
     * retains the explicitly accepted per-game transport availability limitation.
     *
     * @param invocations fully built callback attempts for one committed state
     */
    private void invokeConcurrently(List<CallbackInvocation> invocations) {
        List<Thread> threads = new ArrayList<>(invocations.size());
        for (CallbackInvocation invocation : invocations) {
            threads.add(Thread.startVirtualThread(() -> invokeCallback(invocation)));
        }
        boolean interrupted = false;
        for (Thread thread : threads) {
            boolean joined = false;
            while (!joined) {
                try {
                    thread.join();
                    joined = true;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Performs one remote callback attempt without any GameSession monitor being held.
     * Callback transport failure is logged and does not roll back authoritative state.
     *
     * @param invocation callback target and already-built authoritative snapshot
     */
    private void invokeCallback(CallbackInvocation invocation) {
        try {
            if (invocation.matchFound()) {
                invocation.callback().onMatchFound(invocation.view());
            } else {
                invocation.callback().onGameStateChanged(invocation.view());
            }
        } catch (RemoteException exception) {
            LOGGER.log(Level.WARNING, "Game callback failed for player "
                    + invocation.playerId(), exception);
        }
    }

    /**
     * Resolves and validates an indexed player game.
     *
     * @param playerId participant whose active game is requested
     * @return indexed game containing the participant, or null when none exists
     */
    private GameSession findGame(UUID playerId) {
        GameSession game = gameSessionManager.findByPlayerId(playerId).orElse(null);
        return game != null && game.containsPlayer(playerId) ? game : null;
    }

    /**
     * Returns the fair D-013 lock retained as the mutation and callback execution boundary.
     *
     * @param gameId game whose delivery lane is requested
     * @return stable fair per-game lock
     */
    private ReentrantLock deliveryLane(UUID gameId) {
        return deliveryLanes.computeIfAbsent(gameId, ignored -> new ReentrantLock(true));
    }

    /**
     * Cancels known wake-ups, stops new command admissions, and waits for already admitted work
     * before closing the owned scheduler. Keeping the scheduler open while the sequencer drains lets
     * an admitted state transition replace its deadline and deliver callbacks during normal shutdown.
     */
    @Override
    public void close() {
        for (DeadlineScheduler.Handle handle : deadlineHandles.values()) {
            handle.cancel();
        }
        deadlineHandles.clear();
        sequencer.close();
        if (ownsDeadlineScheduler) {
            deadlineScheduler.close();
        }
    }

    /**
     * Owns one required first-FIFO activation from lifecycle reservation through gated execution.
     * A prepared activation is thread-confined until admission because its reservation holds a
     * sequencer lifecycle lock owned by the creating thread.
     */
    public final class PreparedActivation implements AutoCloseable {
        private final GameCommandSequencer.PreparedAdmission reservation;
        private GameCommandSequencer.AdmittedCommand admittedCommand;

        /**
         * Stores the sequencer reservation created before game indexing.
         *
         * @param reservation mandatory sequencer lifecycle reservation
         */
        private PreparedActivation(GameCommandSequencer.PreparedAdmission reservation) {
            this.reservation = Objects.requireNonNull(reservation, "reservation");
        }

        /**
         * Admits activation as the first command for the newly indexed game while execution remains
         * gated from callbacks and deadline work.
         *
         * @param game newly indexed pre-activation game
         * @throws IllegalStateException if activation was already admitted
         */
        public void admit(GameSession game) {
            Objects.requireNonNull(game, "game");
            if (admittedCommand != null) {
                throw new IllegalStateException("activation is already admitted");
            }
            admittedCommand = reservation.admitFirst(
                    game.getGameId(), () -> executeActivation(game));
        }

        /**
         * Opens the already admitted activation after matchmaking synchronization is released and
         * waits for normal D-013 activation and callback delivery to finish.
         *
         * @throws IllegalStateException if no game was admitted
         */
        public void releaseAndAwait() {
            if (admittedCommand == null) {
                throw new IllegalStateException("activation has not been admitted");
            }
            admittedCommand.releaseAndAwait();
        }

        /**
         * Releases a reservation that was not consumed because game creation failed.
         */
        @Override
        public void close() {
            reservation.close();
        }
    }

    /**
     * Carries one already-built authoritative snapshot to its remote callback.
     *
     * @param playerId callback participant identifier
     * @param callback remote callback target
     * @param view already-built authoritative participant-specific snapshot
     * @param matchFound true for initial onMatchFound delivery, false for state-change delivery
     */
    private record CallbackInvocation(UUID playerId, FleetLinkClientCallback callback,
                                      GameView view, boolean matchFound) {
    }
}
