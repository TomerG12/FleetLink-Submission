package io.github.tomerg12.fleetlink.client.integration;

import java.util.Objects;
import java.util.UUID;

import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;

/**
 * Holds immutable client-only rematch interaction state for one exact completed-game screen.
 * The authoritative transport status remains separate from local operation ownership,
 * acknowledgement, creation commitment, and recoverable feedback.
 */
public final class RematchClientState {

    /** Identifies the one local mutation currently owned by the rematch operation stream. */
    public enum InFlightAction {
        /** No local rematch mutation is unresolved. */
        NONE,
        /** A positive rematch request is unresolved. */
        REQUEST,
        /** Acceptance of an incoming request is unresolved. */
        ACCEPT,
        /** Decline of an incoming request is unresolved. */
        DECLINE,
        /** Withdrawal of the local request is unresolved. */
        WITHDRAW
    }

    /** Identifies the stable presentation mode derived from local and authoritative state. */
    public enum Presentation {
        /** No rematch interaction has started. */
        INITIAL,
        /** A positive request is being sent. */
        REQUEST_IN_FLIGHT,
        /** A positive request succeeded before an authoritative status callback arrived. */
        REQUEST_ACKNOWLEDGED,
        /** The server reports that the local player requested the rematch. */
        REQUESTED_BY_YOU,
        /** The server reports that the opponent requested the rematch. */
        REQUESTED_BY_OPPONENT,
        /** An incoming request is being accepted. */
        ACCEPT_IN_FLIGHT,
        /** An incoming request is being declined. */
        DECLINE_IN_FLIGHT,
        /** A local request is being withdrawn. */
        WITHDRAW_IN_FLIGHT,
        /** The negotiation ended in a decline. */
        DECLINED,
        /** The negotiation expired or was withdrawn. */
        EXPIRED,
        /** A current operation produced feedback while the screen remains usable. */
        RECOVERABLE_FAILURE,
        /** Creation is committed and only a new-game callback may navigate. */
        AWAITING_NEW_GAME
    }

    private final UUID sessionId;
    private final UUID completedGameId;
    private final RematchStatusView authoritativeStatus;
    private final InFlightAction inFlightAction;
    private final boolean requestAcknowledged;
    private final boolean declineAcknowledged;
    private final boolean creationCommitted;
    private final ResultCode feedbackCode;
    private final boolean transportFailure;
    private final String feedbackMessage;

    /**
     * Creates one validated immutable rematch slice for coordinator-owned transitions.
     *
     * @param sessionId exact session that owns this Game Over activation
     * @param completedGameId exact completed game shown by the activation
     * @param authoritativeStatus newest correlated server status, or null before one arrives
     * @param inFlightAction current owner of the single local mutation stream
     * @param requestAcknowledged whether the server successfully returned from a positive request
     * @param declineAcknowledged whether a decline succeeded before its terminal callback arrived
     * @param creationCommitted whether available evidence proves that a new game is being created
     * @param feedbackCode structured expected failure code, or null for no expected failure
     * @param transportFailure whether the feedback describes an unknown transport outcome
     * @param feedbackMessage recoverable player-facing feedback, or an empty string
     * @throws NullPointerException if required identity, action, or message data is null
     * @throws IllegalArgumentException if an authoritative status belongs to another completed game
     */
    RematchClientState(UUID sessionId, UUID completedGameId,
                       RematchStatusView authoritativeStatus, InFlightAction inFlightAction,
                       boolean requestAcknowledged, boolean declineAcknowledged,
                       boolean creationCommitted, ResultCode feedbackCode,
                       boolean transportFailure, String feedbackMessage) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.completedGameId = Objects.requireNonNull(completedGameId, "completedGameId");
        if (authoritativeStatus != null
                && !completedGameId.equals(authoritativeStatus.getCompletedGameId())) {
            throw new IllegalArgumentException("Rematch status belongs to another completed game");
        }
        this.authoritativeStatus = authoritativeStatus;
        this.inFlightAction = Objects.requireNonNull(inFlightAction, "inFlightAction");
        this.requestAcknowledged = requestAcknowledged;
        this.declineAcknowledged = declineAcknowledged;
        this.creationCommitted = creationCommitted;
        this.feedbackCode = feedbackCode;
        this.transportFailure = transportFailure;
        this.feedbackMessage = Objects.requireNonNull(feedbackMessage, "feedbackMessage");
    }

    /**
     * Creates the untouched rematch slice for a newly activated completed game.
     *
     * @param sessionId exact active session
     * @param completedGameId exact completed game
     * @return initial rematch interaction state
     */
    static RematchClientState initial(UUID sessionId, UUID completedGameId) {
        return new RematchClientState(sessionId, completedGameId, null, InFlightAction.NONE,
                false, false, false, null, false, "");
    }

    /**
     * Returns the session identity captured when this Game Over scope was activated.
     *
     * @return exact session identifier
     */
    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Returns the completed game to which every status and operation must correlate.
     *
     * @return exact completed-game identifier
     */
    public UUID getCompletedGameId() {
        return completedGameId;
    }

    /**
     * Returns the newest authoritative status accepted after all callback correlation checks.
     *
     * @return authoritative rematch status, or null before one arrives
     */
    public RematchStatusView getAuthoritativeStatus() {
        return authoritativeStatus;
    }

    /**
     * Returns the one unresolved local mutation, which owns all rematch controls until retired.
     *
     * @return current in-flight action
     */
    public InFlightAction getInFlightAction() {
        return inFlightAction;
    }

    /**
     * Reports whether a positive request succeeded even when its callback has not arrived.
     *
     * @return true when the request operation was acknowledged
     */
    public boolean isRequestAcknowledged() {
        return requestAcknowledged;
    }

    /**
     * Reports whether decline success supplied local terminal evidence before its callback.
     *
     * @return true when the current incoming request was successfully declined
     */
    public boolean isDeclineAcknowledged() {
        return declineAcknowledged;
    }

    /**
     * Reports whether the client must wait for onMatchFound instead of exposing more mutations.
     *
     * @return true when new-game creation is known to be committed
     */
    public boolean isCreationCommitted() {
        return creationCommitted;
    }

    /**
     * Returns a structured expected failure without requiring presentation code to parse text.
     *
     * @return expected failure code, or null when feedback is absent or transport-based
     */
    public ResultCode getFeedbackCode() {
        return feedbackCode;
    }

    /**
     * Reports whether the current feedback represents an unknown transport outcome.
     *
     * @return true for recoverable transport feedback
     */
    public boolean isTransportFailure() {
        return transportFailure;
    }

    /**
     * Returns recoverable expected-operation or transport feedback.
     *
     * @return feedback text, or an empty string when none exists
     */
    public String getFeedbackMessage() {
        return feedbackMessage;
    }

    /**
     * Derives the screen presentation while keeping authoritative and local evidence distinct.
     *
     * @return current rematch presentation mode
     */
    public Presentation getPresentation() {
        if (creationCommitted) {
            return Presentation.AWAITING_NEW_GAME;
        }
        if (inFlightAction != InFlightAction.NONE) {
            return switch (inFlightAction) {
                case REQUEST -> Presentation.REQUEST_IN_FLIGHT;
                case ACCEPT -> Presentation.ACCEPT_IN_FLIGHT;
                case DECLINE -> Presentation.DECLINE_IN_FLIGHT;
                case WITHDRAW -> Presentation.WITHDRAW_IN_FLIGHT;
                case NONE -> throw new IllegalStateException("NONE is not in flight");
            };
        }
        if (!feedbackMessage.isEmpty()) {
            return Presentation.RECOVERABLE_FAILURE;
        }
        if (declineAcknowledged) {
            return Presentation.DECLINED;
        }
        if (authoritativeStatus == null) {
            return requestAcknowledged
                    ? Presentation.REQUEST_ACKNOWLEDGED : Presentation.INITIAL;
        }
        return switch (authoritativeStatus.getState()) {
            case AVAILABLE -> Presentation.INITIAL;
            case REQUESTED_BY_YOU -> Presentation.REQUESTED_BY_YOU;
            case REQUESTED_BY_OPPONENT -> Presentation.REQUESTED_BY_OPPONENT;
            case ACCEPTED -> Presentation.AWAITING_NEW_GAME;
            case DECLINED -> Presentation.DECLINED;
            case EXPIRED -> Presentation.EXPIRED;
        };
    }

    /**
     * Reports whether a fresh positive request is semantically safe from the newest evidence.
     *
     * @return true when the Request control may be enabled
     */
    public boolean canRequest() {
        if (creationCommitted || inFlightAction != InFlightAction.NONE
                || requestAcknowledged || declineAcknowledged) {
            return false;
        }
        if (feedbackCode == ResultCode.REMATCH_NOT_AVAILABLE
                || feedbackCode == ResultCode.REMATCH_ALREADY_PENDING) {
            return false;
        }
        return authoritativeStatus == null
                || authoritativeStatus.getState() == RematchState.AVAILABLE;
    }

    /**
     * Reports whether the current authoritative incoming request may be accepted.
     *
     * @return true when Accept may start a new remote mutation
     */
    public boolean canAccept() {
        return hasActionableIncomingRequest();
    }

    /**
     * Reports whether the current authoritative incoming request may be declined.
     *
     * @return true when Decline may start a new remote mutation
     */
    public boolean canDecline() {
        return hasActionableIncomingRequest();
    }

    /**
     * Reports whether local Lobby return is still available for this activation.
     *
     * @return false only after creation commitment
     */
    public boolean canReturnToLobby() {
        return !creationCommitted;
    }

    /**
     * Checks the shared Accept and Decline preconditions.
     *
     * @return true for a current incoming request with no unresolved local mutation
     */
    private boolean hasActionableIncomingRequest() {
        return !creationCommitted && !declineAcknowledged
                && inFlightAction == InFlightAction.NONE
                && feedbackCode != ResultCode.REMATCH_NOT_AVAILABLE
                && feedbackCode != ResultCode.REMATCH_ALREADY_PENDING
                && authoritativeStatus != null
                && authoritativeStatus.getState() == RematchState.REQUESTED_BY_OPPONENT;
    }
}
