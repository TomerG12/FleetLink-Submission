package io.github.tomerg12.fleetlink.server.rematch;

import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.game.TerminalGameSnapshot;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates one process-memory rematch opportunity per completed game. The coordinator owns only
 * negotiation state and a one-shot creation claim. Final availability and new-game construction are
 * delegated outside the rematch lock to the ordinary matchmaking creation boundary.
 */
public final class RematchCoordinator {
    private static final Logger LOGGER = Logger.getLogger(RematchCoordinator.class.getName());

    private final Object lock = new Object();
    private final Map<UUID, RematchNegotiation> negotiationByCompletedGameId = new HashMap<>();
    private final Map<UUID, UUID> negotiationGameIdByPlayerId = new HashMap<>();
    private final SessionRegistry sessionRegistry;
    private final GameSessionManager gameSessionManager;
    private final MatchmakingService matchmakingService;
    private final ClientCallbackRegistry callbackRegistry;

    /**
     * Creates process-memory rematch coordination over the existing session, game, matchmaking, and
     * callback authorities.
     *
     * @param sessionRegistry single authoritative process session registry
     * @param gameSessionManager authoritative game indexes
     * @param matchmakingService ordinary synchronized game-creation owner
     * @param callbackRegistry current callback registry
     */
    public RematchCoordinator(SessionRegistry sessionRegistry,
                              GameSessionManager gameSessionManager,
                              MatchmakingService matchmakingService,
                              ClientCallbackRegistry callbackRegistry) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.gameSessionManager = Objects.requireNonNull(
                gameSessionManager, "gameSessionManager");
        this.matchmakingService = Objects.requireNonNull(
                matchmakingService, "matchmakingService");
        this.callbackRegistry = Objects.requireNonNull(callbackRegistry, "callbackRegistry");
    }

    /**
     * Records positive intent for the caller's current authoritative completed-game opportunity.
     * A first request sends player-specific pending callbacks. A second participant request claims
     * the same creation path as an explicit acceptance.
     *
     * @param sessionId exact caller session
     * @return synchronous authoritative business result
     */
    public OperationResult requestRematch(UUID sessionId) {
        SessionInfo callerSession = sessionRegistry.findSession(sessionId).orElse(null);
        if (callerSession == null) {
            return invalidSession();
        }
        UUID callerId = callerSession.getPlayer().getPlayerId();
        Opportunity opportunity = resolveOpportunity(sessionId, callerId);
        Transition transition;
        synchronized (lock) {
            RematchNegotiation indexed = negotiationForPlayer(callerId);
            if (isExactParticipant(indexed, sessionId, callerId)
                    && indexed.lifecycle == Lifecycle.OPEN && indexed.intent(callerId)) {
                transition = isNegotiationCurrent(indexed)
                        ? Transition.result(OperationResult.success()) : expireOpen(indexed);
            } else if (opportunity == null || !isOpportunityCurrent(opportunity)) {
                transition = expireOpen(indexed);
            } else {
                transition = recordRequest(opportunity, callerId);
            }
        }
        return completeTransition(transition);
    }

    /**
     * Applies a positive response, decline, or requester withdrawal to the caller's current exact
     * negotiation. Positive response never creates a negotiation from nothing. False after creation
     * claim cannot cancel or roll back game creation.
     *
     * @param sessionId exact caller session
     * @param accept true for positive agreement, false for decline or withdrawal
     * @return synchronous authoritative business result
     */
    public OperationResult respondToRematch(UUID sessionId, boolean accept) {
        SessionInfo callerSession = sessionRegistry.findSession(sessionId).orElse(null);
        if (callerSession == null) {
            return invalidSession();
        }
        UUID callerId = callerSession.getPlayer().getPlayerId();
        Opportunity currentOpportunity = accept ? null : resolveOpportunity(sessionId, callerId);
        Transition transition;
        synchronized (lock) {
            RematchNegotiation negotiation = negotiationForPlayer(callerId);
            if (!accept && currentOpportunity != null
                    && (negotiation == null || !negotiation.matches(currentOpportunity))) {
                if (!isOpportunityCurrent(currentOpportunity)) {
                    return rematchUnavailable();
                }
                negotiation = indexNegotiation(currentOpportunity);
            }
            if (!isExactParticipant(negotiation, sessionId, callerId)) {
                return rematchUnavailable();
            }
            if (accept) {
                transition = respondPositive(negotiation, callerId);
            } else {
                transition = respondNegative(negotiation, callerId);
            }
        }
        return completeTransition(transition);
    }

    /**
     * Expires the completed-game opportunity of a participant who has forfeited or whose exact
     * session is terminating. This internal lifecycle hook may run after request authority was
     * removed, but it still binds the terminal marker to the supplied session and completed game.
     * Creation that already crossed the one-shot claim is never rolled back.
     *
     * @param sessionId exact departing session
     * @param playerId player owned by the departing session
     */
    public void abandonCompletedGame(UUID sessionId, UUID playerId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(playerId, "playerId");
        Opportunity opportunity = resolveAbandonmentOpportunity(sessionId, playerId);
        if (opportunity == null) {
            return;
        }
        Transition transition;
        synchronized (lock) {
            RematchNegotiation negotiation = negotiationForPlayer(playerId);
            if (negotiation == null || !negotiation.matches(opportunity)) {
                if (!isAbandonmentCurrent(opportunity, playerId)) {
                    return;
                }
                negotiation = indexNegotiation(opportunity);
            }
            if (!isExactParticipant(negotiation, sessionId, playerId)
                    || negotiation.lifecycle != Lifecycle.OPEN) {
                return;
            }
            negotiation.lifecycle = Lifecycle.EXPIRED;
            negotiation.terminalResponderId = playerId;
            transition = Transition.notify(negotiation, RematchState.EXPIRED,
                    OperationResult.success());
        }
        deliver(transition.notifications());
    }

    /**
     * Expires an open negotiation owned by an exact ending session. Creation that already claimed
     * the ordinary matchmaking boundary is not rolled back.
     *
     * @param sessionId exact ending session
     * @param playerId player owned by the ending session
     */
    public void terminateSession(UUID sessionId, UUID playerId) {
        Transition transition;
        synchronized (lock) {
            RematchNegotiation negotiation = negotiationForPlayer(playerId);
            if (!isExactParticipant(negotiation, sessionId, playerId)
                    || negotiation.lifecycle != Lifecycle.OPEN) {
                return;
            }
            negotiation.lifecycle = Lifecycle.EXPIRED;
            transition = Transition.notify(negotiation, RematchState.EXPIRED,
                    OperationResult.success());
        }
        deliver(transition.notifications());
    }

    /**
     * Expires an open opportunity after ordinary matchmaking successfully commits for a participant.
     * Callback failure cannot change the already committed matchmaking transition.
     *
     * @param playerId player whose ordinary matchmaking state advanced
     */
    public void expireForOrdinaryMatchmaking(UUID playerId) {
        Transition transition;
        synchronized (lock) {
            RematchNegotiation negotiation = negotiationForPlayer(playerId);
            if (negotiation == null || negotiation.lifecycle != Lifecycle.OPEN) {
                return;
            }
            negotiation.lifecycle = Lifecycle.EXPIRED;
            transition = Transition.notify(negotiation, RematchState.EXPIRED,
                    OperationResult.success());
        }
        deliver(transition.notifications());
    }

    /**
     * Returns the number of one-shot creation claims recorded for deterministic package tests.
     *
     * @param completedGameId completed source identifier
     * @return zero or one for an indexed negotiation
     */
    int creationClaimCount(UUID completedGameId) {
        synchronized (lock) {
            RematchNegotiation negotiation = negotiationByCompletedGameId.get(completedGameId);
            return negotiation == null ? 0 : negotiation.creationClaimCount;
        }
    }

    /**
     * Returns the internal lifecycle name for deterministic package tests without exposing it over
     * RMI.
     *
     * @param completedGameId completed source identifier
     * @return lifecycle name, or an empty string when no negotiation exists
     */
    String lifecycleName(UUID completedGameId) {
        synchronized (lock) {
            RematchNegotiation negotiation = negotiationByCompletedGameId.get(completedGameId);
            return negotiation == null ? "" : negotiation.lifecycle.name();
        }
    }

    /**
     * Records a request against a verified opportunity or resolves an existing negotiation.
     * This method is called only while holding the rematch lock.
     *
     * @param opportunity current completed source and exact participants
     * @param callerId requesting participant
     * @return committed transition
     */
    private Transition recordRequest(Opportunity opportunity, UUID callerId) {
        RematchNegotiation negotiation = indexNegotiation(opportunity);
        if (!negotiation.matches(opportunity) || negotiation.lifecycle != Lifecycle.OPEN) {
            return Transition.result(rematchUnavailable());
        }
        if (negotiation.intent(callerId)) {
            return Transition.result(OperationResult.success());
        }
        negotiation.setIntent(callerId);
        UUID opponentId = negotiation.opponentId(callerId);
        if (!negotiation.intent(opponentId)) {
            return Transition.firstRequest(negotiation, callerId);
        }
        return claimCreation(negotiation);
    }

    /**
     * Returns or creates the exact negotiation indexed by one verified opportunity.
     * This method is called only while holding the rematch lock.
     *
     * @param opportunity verified completed-game opportunity
     * @return existing or newly indexed negotiation
     */
    private RematchNegotiation indexNegotiation(Opportunity opportunity) {
        RematchNegotiation negotiation = negotiationByCompletedGameId.get(
                opportunity.completedGameId());
        if (negotiation != null) {
            return negotiation;
        }
        expireConflictingPlayerNegotiation(opportunity.playerOne().getPlayerId());
        expireConflictingPlayerNegotiation(opportunity.playerTwo().getPlayerId());
        negotiation = new RematchNegotiation(opportunity);
        negotiationByCompletedGameId.put(opportunity.completedGameId(), negotiation);
        negotiationGameIdByPlayerId.put(
                opportunity.playerOne().getPlayerId(), opportunity.completedGameId());
        negotiationGameIdByPlayerId.put(
                opportunity.playerTwo().getPlayerId(), opportunity.completedGameId());
        return negotiation;
    }

    /**
     * Applies positive agreement to an existing exact negotiation.
     *
     * @param negotiation current negotiation
     * @param callerId responding participant
     * @return committed transition
     */
    private Transition respondPositive(RematchNegotiation negotiation, UUID callerId) {
        if (negotiation.lifecycle == Lifecycle.CREATION_CLAIMED
                && negotiation.intent(callerId)) {
            return Transition.result(OperationResult.success());
        }
        if (negotiation.lifecycle != Lifecycle.OPEN || !isNegotiationCurrent(negotiation)) {
            Transition expired = expireOpen(negotiation);
            return expired.notifications().isEmpty()
                    ? Transition.result(rematchUnavailable())
                    : new Transition(rematchUnavailable(), expired.notifications(), null);
        }
        if (negotiation.intent(callerId)) {
            return Transition.result(OperationResult.success());
        }
        UUID opponentId = negotiation.opponentId(callerId);
        if (!negotiation.intent(opponentId)) {
            return Transition.result(rematchUnavailable());
        }
        negotiation.setIntent(callerId);
        return claimCreation(negotiation);
    }

    /**
     * Applies decline or withdrawal according to sole positive intent ownership.
     *
     * @param negotiation current negotiation
     * @param callerId responding participant
     * @return committed transition
     */
    private Transition respondNegative(RematchNegotiation negotiation, UUID callerId) {
        if ((negotiation.lifecycle == Lifecycle.DECLINED
                || negotiation.lifecycle == Lifecycle.EXPIRED)
                && callerId.equals(negotiation.terminalResponderId)) {
            return Transition.result(OperationResult.success());
        }
        if (negotiation.lifecycle != Lifecycle.OPEN) {
            return Transition.result(rematchUnavailable());
        }
        if (!isNegotiationCurrent(negotiation)) {
            Transition expired = expireOpen(negotiation);
            return new Transition(rematchUnavailable(), expired.notifications(), null);
        }
        UUID opponentId = negotiation.opponentId(callerId);
        boolean callerIntent = negotiation.intent(callerId);
        boolean opponentIntent = negotiation.intent(opponentId);
        if (!callerIntent && opponentIntent) {
            negotiation.lifecycle = Lifecycle.DECLINED;
            negotiation.terminalResponderId = callerId;
            return Transition.notify(negotiation, RematchState.DECLINED,
                    OperationResult.success());
        }
        if (callerIntent && !opponentIntent) {
            negotiation.lifecycle = Lifecycle.EXPIRED;
            negotiation.terminalResponderId = callerId;
            return Transition.notify(negotiation, RematchState.EXPIRED,
                    OperationResult.success());
        }
        if (!callerIntent && !opponentIntent) {
            negotiation.lifecycle = Lifecycle.EXPIRED;
            negotiation.terminalResponderId = callerId;
            return Transition.notify(negotiation, RematchState.EXPIRED,
                    OperationResult.success());
        }
        return Transition.result(rematchUnavailable());
    }

    /**
     * Claims the one permitted final creation attempt.
     *
     * @param negotiation mutually positive open negotiation
     * @return transition carrying the claim owner work
     */
    private Transition claimCreation(RematchNegotiation negotiation) {
        if (negotiation.creationClaimed) {
            return Transition.result(OperationResult.success());
        }
        negotiation.creationClaimed = true;
        negotiation.creationClaimCount++;
        negotiation.lifecycle = Lifecycle.CREATION_CLAIMED;
        return Transition.claim(negotiation);
    }

    /**
     * Runs final creation outside the rematch lock and then commits CREATED or EXPIRED before any
     * rematch callback attempt.
     *
     * @param transition committed negotiation transition
     * @return synchronous result for the triggering operation
     */
    private OperationResult completeTransition(Transition transition) {
        if (transition.claim() == null) {
            deliver(transition.notifications());
            return transition.result();
        }
        RematchNegotiation negotiation = transition.claim();
        OperationResult creation = matchmakingService.createRematch(
                negotiation.completedGameId,
                negotiation.playerOneSessionId, negotiation.playerOne,
                negotiation.playerTwoSessionId, negotiation.playerTwo);
        List<Notification> notifications;
        OperationResult result;
        synchronized (lock) {
            if (creation.isSuccess()) {
                negotiation.lifecycle = Lifecycle.CREATED;
                notifications = notifications(negotiation, RematchState.ACCEPTED, false);
                result = OperationResult.success();
            } else {
                negotiation.lifecycle = Lifecycle.EXPIRED;
                notifications = notifications(negotiation, RematchState.EXPIRED, false);
                result = rematchUnavailable();
            }
        }
        deliver(notifications);
        return result;
    }

    /**
     * Resolves an exact eligible terminal source without consulting persistence.
     *
     * @param callerSessionId exact caller session
     * @param callerId caller player identifier
     * @return current opportunity, or null when unavailable
     */
    private Opportunity resolveOpportunity(UUID callerSessionId, UUID callerId) {
        GameSession source = gameSessionManager.findByPlayerId(callerId).orElse(null);
        if (source == null || !source.isFinished() || gameSessionManager.hasActiveGame(callerId)) {
            return null;
        }
        TerminalGameSnapshot terminal;
        try {
            terminal = source.captureTerminalState();
        } catch (IllegalStateException exception) {
            return null;
        }
        PlayerView playerOne = terminal.getPlayerOne();
        PlayerView playerTwo = terminal.getPlayerTwo();
        if (!source.containsPlayer(callerId)
                || matchmakingService.isWaiting(playerOne.getPlayerId())
                || matchmakingService.isWaiting(playerTwo.getPlayerId())) {
            return null;
        }
        SessionInfo playerOneSession = sessionRegistry.findSessionByPlayerId(
                playerOne.getPlayerId()).orElse(null);
        SessionInfo playerTwoSession = sessionRegistry.findSessionByPlayerId(
                playerTwo.getPlayerId()).orElse(null);
        if (playerOneSession == null || playerTwoSession == null
                || callbackRegistry.find(playerOne.getPlayerId()).isEmpty()
                || callbackRegistry.find(playerTwo.getPlayerId()).isEmpty()) {
            return null;
        }
        UUID expectedCallerSession = callerId.equals(playerOne.getPlayerId())
                ? playerOneSession.getSessionId() : playerTwoSession.getSessionId();
        if (!callerSessionId.equals(expectedCallerSession)) {
            return null;
        }
        return new Opportunity(source.getGameId(), playerOne, playerTwo,
                playerOneSession.getSessionId(), playerTwoSession.getSessionId());
    }

    /**
     * Resolves a terminal source for lifecycle abandonment while allowing the departing exact
     * session to have already left the active registry. The opponent must still own an active
     * callback-capable session so expiration can be observed.
     *
     * @param departingSessionId exact departing session
     * @param departingPlayerId departing participant
     * @return abandonment opportunity, or null when no exact current source remains
     */
    private Opportunity resolveAbandonmentOpportunity(UUID departingSessionId,
                                                       UUID departingPlayerId) {
        GameSession source = gameSessionManager.findByPlayerId(departingPlayerId).orElse(null);
        if (source == null || !source.isFinished()
                || gameSessionManager.hasActiveGame(departingPlayerId)) {
            return null;
        }
        TerminalGameSnapshot terminal;
        try {
            terminal = source.captureTerminalState();
        } catch (IllegalStateException exception) {
            return null;
        }
        PlayerView playerOne = terminal.getPlayerOne();
        PlayerView playerTwo = terminal.getPlayerTwo();
        if (!source.containsPlayer(departingPlayerId)
                || matchmakingService.isWaiting(playerOne.getPlayerId())
                || matchmakingService.isWaiting(playerTwo.getPlayerId())) {
            return null;
        }
        UUID opponentId = departingPlayerId.equals(playerOne.getPlayerId())
                ? playerTwo.getPlayerId() : playerOne.getPlayerId();
        SessionInfo opponentSession = sessionRegistry.findSessionByPlayerId(opponentId).orElse(null);
        if (opponentSession == null || callbackRegistry.find(opponentId).isEmpty()) {
            return null;
        }
        SessionInfo currentDeparting = sessionRegistry.findSessionByPlayerId(
                departingPlayerId).orElse(null);
        if (currentDeparting != null
                && !departingSessionId.equals(currentDeparting.getSessionId())) {
            return null;
        }
        UUID playerOneSessionId = departingPlayerId.equals(playerOne.getPlayerId())
                ? departingSessionId : opponentSession.getSessionId();
        UUID playerTwoSessionId = departingPlayerId.equals(playerTwo.getPlayerId())
                ? departingSessionId : opponentSession.getSessionId();
        return new Opportunity(source.getGameId(), playerOne, playerTwo,
                playerOneSessionId, playerTwoSessionId);
    }

    /**
     * Revalidates a newly resolved opportunity before committing negotiation state.
     *
     * @param opportunity captured opportunity
     * @return true while source mappings, exact sessions, callbacks, finalization, and absence from
     *         ordinary waiting remain current
     */
    private boolean isOpportunityCurrent(Opportunity opportunity) {
        GameSession source = gameSessionManager.findByGameId(
                opportunity.completedGameId()).orElse(null);
        return source != null && source.isFinished()
                && !gameSessionManager.hasActiveGame(opportunity.playerOne().getPlayerId())
                && !gameSessionManager.hasActiveGame(opportunity.playerTwo().getPlayerId())
                && !matchmakingService.isWaiting(opportunity.playerOne().getPlayerId())
                && !matchmakingService.isWaiting(opportunity.playerTwo().getPlayerId())
                && gameSessionManager.findByPlayerId(
                        opportunity.playerOne().getPlayerId()).orElse(null) == source
                && gameSessionManager.findByPlayerId(
                        opportunity.playerTwo().getPlayerId()).orElse(null) == source
                && isCurrentSession(opportunity.playerOneSessionId(),
                        opportunity.playerOne().getPlayerId())
                && isCurrentSession(opportunity.playerTwoSessionId(),
                        opportunity.playerTwo().getPlayerId())
                && callbackRegistry.find(opportunity.playerOne().getPlayerId()).isPresent()
                && callbackRegistry.find(opportunity.playerTwo().getPlayerId()).isPresent();
    }

    /**
     * Revalidates terminal source identity while permitting only the departing participant's
     * exact session to be absent.
     *
     * @param opportunity captured abandonment opportunity
     * @param departingPlayerId participant leaving the completed-game lifecycle
     * @return true when the source and remaining opponent session are still current
     */
    private boolean isAbandonmentCurrent(Opportunity opportunity, UUID departingPlayerId) {
        GameSession source = gameSessionManager.findByGameId(
                opportunity.completedGameId()).orElse(null);
        UUID opponentId = departingPlayerId.equals(opportunity.playerOne().getPlayerId())
                ? opportunity.playerTwo().getPlayerId() : opportunity.playerOne().getPlayerId();
        UUID opponentSessionId = opponentId.equals(opportunity.playerOne().getPlayerId())
                ? opportunity.playerOneSessionId() : opportunity.playerTwoSessionId();
        return source != null && source.isFinished()
                && !gameSessionManager.hasActiveGame(opportunity.playerOne().getPlayerId())
                && !gameSessionManager.hasActiveGame(opportunity.playerTwo().getPlayerId())
                && !matchmakingService.isWaiting(opportunity.playerOne().getPlayerId())
                && !matchmakingService.isWaiting(opportunity.playerTwo().getPlayerId())
                && gameSessionManager.findByPlayerId(
                        opportunity.playerOne().getPlayerId()).orElse(null) == source
                && gameSessionManager.findByPlayerId(
                        opportunity.playerTwo().getPlayerId()).orElse(null) == source
                && isCurrentSession(opponentSessionId, opponentId)
                && callbackRegistry.find(opponentId).isPresent();
    }

    /**
     * Revalidates a stored negotiation against exact source and session authority.
     *
     * @param negotiation stored negotiation
     * @return true while the old completed source remains current for both exact sessions and
     *         neither participant is waiting for an ordinary match
     */
    private boolean isNegotiationCurrent(RematchNegotiation negotiation) {
        Opportunity opportunity = negotiation.opportunity();
        GameSession source = gameSessionManager.findByGameId(
                opportunity.completedGameId()).orElse(null);
        return source != null && source.isFinished()
                && !gameSessionManager.hasActiveGame(opportunity.playerOne().getPlayerId())
                && !gameSessionManager.hasActiveGame(opportunity.playerTwo().getPlayerId())
                && !matchmakingService.isWaiting(opportunity.playerOne().getPlayerId())
                && !matchmakingService.isWaiting(opportunity.playerTwo().getPlayerId())
                && gameSessionManager.findByPlayerId(
                        opportunity.playerOne().getPlayerId()).orElse(null) == source
                && gameSessionManager.findByPlayerId(
                        opportunity.playerTwo().getPlayerId()).orElse(null) == source
                && isCurrentSession(opportunity.playerOneSessionId(),
                        opportunity.playerOne().getPlayerId())
                && isCurrentSession(opportunity.playerTwoSessionId(),
                        opportunity.playerTwo().getPlayerId());
    }

    /**
     * Checks exact current session ownership through the single session registry.
     *
     * @param sessionId expected session
     * @param playerId expected player
     * @return true when exact ownership remains active
     */
    private boolean isCurrentSession(UUID sessionId, UUID playerId) {
        return sessionRegistry.findSession(sessionId)
                .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                .orElse(false);
    }

    /**
     * Resolves one player's indexed negotiation.
     *
     * @param playerId participant identifier
     * @return indexed negotiation, or null
     */
    private RematchNegotiation negotiationForPlayer(UUID playerId) {
        UUID gameId = negotiationGameIdByPlayerId.get(playerId);
        return gameId == null ? null : negotiationByCompletedGameId.get(gameId);
    }

    /**
     * Checks exact participant and captured-session membership.
     *
     * @param negotiation candidate negotiation
     * @param sessionId exact caller session
     * @param playerId caller player
     * @return true when caller matches the stored binding
     */
    private static boolean isExactParticipant(RematchNegotiation negotiation,
                                              UUID sessionId, UUID playerId) {
        return negotiation != null && negotiation.matchesSession(sessionId, playerId);
    }

    /**
     * Expires an open negotiation and builds notifications.
     *
     * @param negotiation negotiation that may be stale
     * @return expiration transition or plain unavailable result
     */
    private Transition expireOpen(RematchNegotiation negotiation) {
        if (negotiation == null || negotiation.lifecycle != Lifecycle.OPEN) {
            return Transition.result(rematchUnavailable());
        }
        negotiation.lifecycle = Lifecycle.EXPIRED;
        return Transition.notify(negotiation, RematchState.EXPIRED, rematchUnavailable());
    }

    /**
     * Expires an older open player negotiation before indexing a newer completed source.
     *
     * @param playerId player whose prior index may conflict
     */
    private void expireConflictingPlayerNegotiation(UUID playerId) {
        RematchNegotiation existing = negotiationForPlayer(playerId);
        if (existing != null && existing.lifecycle == Lifecycle.OPEN) {
            existing.lifecycle = Lifecycle.EXPIRED;
        }
    }

    /**
     * Builds participant-specific status notifications.
     *
     * @param negotiation authoritative negotiation
     * @param state public state delivered to both participants
     * @param requestOpponent true to use onRematchRequested for the opponent notification
     * @return immutable callback work list
     */
    private List<Notification> notifications(RematchNegotiation negotiation,
                                             RematchState state,
                                             boolean requestOpponent) {
        List<Notification> notifications = new ArrayList<>(2);
        notifications.add(new Notification(negotiation.playerOne.getPlayerId(),
                negotiation.playerOneSessionId,
                negotiation.statusFor(negotiation.playerOne.getPlayerId(), state), false));
        notifications.add(new Notification(negotiation.playerTwo.getPlayerId(),
                negotiation.playerTwoSessionId,
                negotiation.statusFor(negotiation.playerTwo.getPlayerId(), state),
                requestOpponent));
        return List.copyOf(notifications);
    }

    /**
     * Performs best-effort callback delivery with no coordinator lock held.
     *
     * @param notifications already-built authoritative notifications
     */
    private void deliver(List<Notification> notifications) {
        for (Notification notification : notifications) {
            if (!isCurrentSession(notification.sessionId(), notification.playerId())) {
                continue;
            }
            FleetLinkClientCallback callback = callbackRegistry.find(
                    notification.playerId()).orElse(null);
            if (callback == null) {
                continue;
            }
            try {
                if (notification.requestedCallback()) {
                    callback.onRematchRequested(notification.status());
                } else {
                    callback.onRematchStatusChanged(notification.status());
                }
            } catch (RemoteException exception) {
                LOGGER.log(Level.WARNING, "Rematch callback failed for player "
                        + notification.playerId(), exception);
            }
        }
    }

    /**
     * Creates the standard invalid-session result.
     *
     * @return INVALID_SESSION result
     */
    private static OperationResult invalidSession() {
        return OperationResult.failure(ResultCode.INVALID_SESSION, "Invalid session");
    }

    /**
     * Creates the standard unavailable result.
     *
     * @return REMATCH_NOT_AVAILABLE result
     */
    private static OperationResult rematchUnavailable() {
        return OperationResult.failure(
                ResultCode.REMATCH_NOT_AVAILABLE, "Rematch is not available");
    }

    /** Internal negotiation lifecycle hidden from the RMI protocol. */
    private enum Lifecycle {
        /** Negotiation accepts positive or negative participant input. */
        OPEN,
        /** One operation owns the final ordinary matchmaking creation attempt. */
        CREATION_CLAIMED,
        /** A new normal game was created. */
        CREATED,
        /** A participant explicitly rejected the opportunity. */
        DECLINED,
        /** The opportunity was withdrawn or invalidated. */
        EXPIRED
    }

    /**
     * Captures one eligible completed source and exact active sessions.
     *
     * @param completedGameId completed source identifier
     * @param playerOne first source participant
     * @param playerTwo second source participant
     * @param playerOneSessionId exact first participant session
     * @param playerTwoSessionId exact second participant session
     */
    private record Opportunity(UUID completedGameId, PlayerView playerOne, PlayerView playerTwo,
                               UUID playerOneSessionId, UUID playerTwoSessionId) {
    }

    /** Stores all mutable state for one completed-game opportunity under the rematch lock. */
    private static final class RematchNegotiation {
        private final UUID completedGameId;
        private final PlayerView playerOne;
        private final PlayerView playerTwo;
        private final UUID playerOneSessionId;
        private final UUID playerTwoSessionId;
        private boolean playerOneIntent;
        private boolean playerTwoIntent;
        private boolean creationClaimed;
        private int creationClaimCount;
        private Lifecycle lifecycle = Lifecycle.OPEN;
        private UUID terminalResponderId;

        /**
         * Binds one negotiation to exact completed source facts.
         *
         * @param opportunity verified opportunity
         */
        private RematchNegotiation(Opportunity opportunity) {
            completedGameId = opportunity.completedGameId();
            playerOne = opportunity.playerOne();
            playerTwo = opportunity.playerTwo();
            playerOneSessionId = opportunity.playerOneSessionId();
            playerTwoSessionId = opportunity.playerTwoSessionId();
        }

        /**
         * Rebuilds the immutable opportunity represented by this negotiation.
         *
         * @return stored exact opportunity
         */
        private Opportunity opportunity() {
            return new Opportunity(completedGameId, playerOne, playerTwo,
                    playerOneSessionId, playerTwoSessionId);
        }

        /**
         * Checks equality with newly resolved source facts.
         *
         * @param opportunity newly resolved opportunity
         * @return true when source, participants, and sessions match exactly
         */
        private boolean matches(Opportunity opportunity) {
            return completedGameId.equals(opportunity.completedGameId())
                    && playerOne.getPlayerId().equals(opportunity.playerOne().getPlayerId())
                    && playerTwo.getPlayerId().equals(opportunity.playerTwo().getPlayerId())
                    && playerOneSessionId.equals(opportunity.playerOneSessionId())
                    && playerTwoSessionId.equals(opportunity.playerTwoSessionId());
        }

        /**
         * Checks exact player and session membership.
         *
         * @param sessionId exact session
         * @param playerId player identifier
         * @return true when the pair matches a stored participant binding
         */
        private boolean matchesSession(UUID sessionId, UUID playerId) {
            return (playerOne.getPlayerId().equals(playerId)
                    && playerOneSessionId.equals(sessionId))
                    || (playerTwo.getPlayerId().equals(playerId)
                    && playerTwoSessionId.equals(sessionId));
        }

        /**
         * Reads positive intent for one participant.
         *
         * @param playerId participant identifier
         * @return stored intent
         */
        private boolean intent(UUID playerId) {
            return playerOne.getPlayerId().equals(playerId)
                    ? playerOneIntent : playerTwoIntent;
        }

        /**
         * Records positive intent for one participant.
         *
         * @param playerId participant identifier
         */
        private void setIntent(UUID playerId) {
            if (playerOne.getPlayerId().equals(playerId)) {
                playerOneIntent = true;
            } else {
                playerTwoIntent = true;
            }
        }

        /**
         * Resolves the other participant.
         *
         * @param playerId one participant identifier
         * @return opponent identifier
         */
        private UUID opponentId(UUID playerId) {
            return playerOne.getPlayerId().equals(playerId)
                    ? playerTwo.getPlayerId() : playerOne.getPlayerId();
        }

        /**
         * Builds a participant-specific public status.
         *
         * @param receiverId receiving participant
         * @param state public state to expose
         * @return receiver-safe rematch status
         */
        private RematchStatusView statusFor(UUID receiverId, RematchState state) {
            PlayerView opponent = playerOne.getPlayerId().equals(receiverId)
                    ? playerTwo : playerOne;
            return new RematchStatusView(completedGameId, opponent, state);
        }
    }

    /**
     * Carries committed rematch work outside the rematch lock.
     *
     * @param result synchronous operation result before optional creation resolution
     * @param notifications callback work ready for delivery
     * @param claim negotiation whose claim owner must run final creation
     */
    private record Transition(OperationResult result, List<Notification> notifications,
                              RematchNegotiation claim) {
        /**
         * Creates a result-only transition.
         *
         * @param result synchronous result
         * @return result transition
         */
        private static Transition result(OperationResult result) {
            return new Transition(result, List.of(), null);
        }

        /**
         * Creates first-request participant-specific callbacks.
         *
         * @param negotiation committed negotiation
         * @param requesterId requesting participant
         * @return first-request transition
         */
        private static Transition firstRequest(RematchNegotiation negotiation, UUID requesterId) {
            UUID opponentId = negotiation.opponentId(requesterId);
            Notification requester = new Notification(requesterId,
                    requesterId.equals(negotiation.playerOne.getPlayerId())
                            ? negotiation.playerOneSessionId : negotiation.playerTwoSessionId,
                    negotiation.statusFor(requesterId, RematchState.REQUESTED_BY_YOU), false);
            Notification opponent = new Notification(opponentId,
                    opponentId.equals(negotiation.playerOne.getPlayerId())
                            ? negotiation.playerOneSessionId : negotiation.playerTwoSessionId,
                    negotiation.statusFor(opponentId, RematchState.REQUESTED_BY_OPPONENT), true);
            return new Transition(OperationResult.success(), List.of(requester, opponent), null);
        }

        /**
         * Creates a creation-claim transition.
         *
         * @param negotiation claimed negotiation
         * @return claim transition
         */
        private static Transition claim(RematchNegotiation negotiation) {
            return new Transition(OperationResult.success(), List.of(), negotiation);
        }

        /**
         * Creates equal status notifications for both participants.
         *
         * @param negotiation terminal negotiation
         * @param state terminal public state
         * @param result synchronous result
         * @return callback transition
         */
        private static Transition notify(RematchNegotiation negotiation, RematchState state,
                                         OperationResult result) {
            Notification first = new Notification(negotiation.playerOne.getPlayerId(),
                    negotiation.playerOneSessionId,
                    negotiation.statusFor(negotiation.playerOne.getPlayerId(), state), false);
            Notification second = new Notification(negotiation.playerTwo.getPlayerId(),
                    negotiation.playerTwoSessionId,
                    negotiation.statusFor(negotiation.playerTwo.getPlayerId(), state), false);
            return new Transition(result, List.of(first, second), null);
        }
    }

    /**
     * Stores one callback target and participant-specific status.
     *
     * @param playerId expected callback player
     * @param sessionId exact session that owns the notification
     * @param status participant-specific status
     * @param requestedCallback true for onRematchRequested, false for status changed
     */
    private record Notification(UUID playerId, UUID sessionId, RematchStatusView status,
                                boolean requestedCallback) {
    }
}
