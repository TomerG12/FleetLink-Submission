package io.github.tomerg12.fleetlink.server.session;

import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Owns active sessions and identity reservations for guest and registered players.
 * Session termination removes request authority before releasing the player identity so cleanup
 * cannot race a replacement registered session.
 */
public final class SessionRegistry {

    private static final int GUEST_SESSION_RATING = 1000;

    private final ConcurrentHashMap<UUID, SessionInfo> sessionsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> sessionByPlayerId = new ConcurrentHashMap<>();
    private final Supplier<UUID> sessionIdSupplier;
    private final Supplier<UUID> playerIdSupplier;
    private final PersistentIdentityLookup persistentIdentityLookup;

    /**
     * Creates an empty registry using random identifiers for sessions and guest players.
     */
    public SessionRegistry() {
        this(UUID::randomUUID, UUID::randomUUID, ignored -> false);
    }

    /**
     * Creates an empty registry that excludes persistent identifiers from guest allocation.
     *
     * @param persistentIdentityLookup persistent identity collision boundary
     */
    public SessionRegistry(PersistentIdentityLookup persistentIdentityLookup) {
        this(UUID::randomUUID, UUID::randomUUID, persistentIdentityLookup);
    }

    /**
     * Creates a registry with injectable identifier sources for deterministic tests.
     *
     * @param sessionIdSupplier the source for opaque session identifiers
     * @param playerIdSupplier the source for temporary guest player identifiers
     * @param persistentIdentityLookup persistent identity collision boundary
     */
    public SessionRegistry(Supplier<UUID> sessionIdSupplier, Supplier<UUID> playerIdSupplier,
                           PersistentIdentityLookup persistentIdentityLookup) {
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        this.playerIdSupplier = Objects.requireNonNull(playerIdSupplier, "playerIdSupplier");
        this.persistentIdentityLookup = Objects.requireNonNull(
                persistentIdentityLookup, "persistentIdentityLookup");
    }

    /**
     * Creates and stores one temporary guest session.
     * The guest receives the fixed non-persistent rating used by the current core server slice.
     * Matchmaking remains responsible for applying its own guest rating policy.
     *
     * @param displayName the non-blank guest display name
     * @return the newly created safe session information
     * @throws NullPointerException if the display name is null
     * @throws IllegalArgumentException if the display name is blank
     */
    public synchronized SessionInfo createGuest(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }

        UUID playerId = nextUniquePlayerId();
        UUID sessionId = nextUniqueSessionId();
        PlayerView player = new PlayerView(playerId, displayName, GUEST_SESSION_RATING, true);
        SessionInfo session = new SessionInfo(sessionId, player);
        sessionsById.put(sessionId, session);
        sessionByPlayerId.put(playerId, sessionId);
        return session;
    }

    /**
     * Atomically claims a temporary session for one persistent player identity.
     * An active or terminating session keeps the identity reserved and causes an empty result.
     *
     * @param player persistent registered player view
     * @return new session information, or empty when the identity is reserved
     * @throws IllegalArgumentException if the supplied player is marked as a guest
     */
    public synchronized Optional<SessionInfo> claimRegistered(PlayerView player) {
        Objects.requireNonNull(player, "player");
        if (player.isGuest()) {
            throw new IllegalArgumentException("registered session requires a non-guest player");
        }
        if (sessionByPlayerId.containsKey(player.getPlayerId())) {
            return Optional.empty();
        }
        UUID sessionId = nextUniqueSessionId();
        SessionInfo session = new SessionInfo(sessionId, player);
        sessionsById.put(sessionId, session);
        sessionByPlayerId.put(player.getPlayerId(), sessionId);
        return Optional.of(session);
    }

    /**
     * Resolves one opaque session identifier to its safe server-owned player information.
     *
     * @param sessionId the session identifier received from a client request
     * @return the associated player when the session exists
     */
    public Optional<PlayerView> resolvePlayer(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        SessionInfo session = sessionsById.get(sessionId);
        return session == null ? Optional.empty() : Optional.of(session.getPlayer());
    }

    /**
     * Resolves one session to its complete safe session information.
     *
     * @param sessionId the session identifier
     * @return the session information when present
     */
    public Optional<SessionInfo> findSession(UUID sessionId) {
        return Optional.ofNullable(sessionId == null ? null : sessionsById.get(sessionId));
    }

    /**
     * Resolves the active session currently associated with one reserved player identity.
     * A terminating identity has no active session even though its reservation remains present.
     *
     * @param playerId player identifier
     * @return active session information when present
     */
    public Optional<SessionInfo> findSessionByPlayerId(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        UUID sessionId = sessionByPlayerId.get(playerId);
        return sessionId == null ? Optional.empty() : findSession(sessionId);
    }

    /**
     * Removes one session and its reverse player index.
     *
     * @param sessionId the session to remove
     * @return the removed safe session information when present
     */
    public synchronized Optional<Termination> beginTermination(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        SessionInfo removed = sessionsById.remove(sessionId);
        return removed == null ? Optional.empty() : Optional.of(new Termination(removed));
    }

    /**
     * Releases an identity after all cleanup belonging to its invalidated session has completed.
     * Compare-and-remove semantics prevent an obsolete termination from releasing another session.
     *
     * @param termination termination handle returned by {@link #beginTermination(UUID)}
     * @return true when the matching identity reservation was released
     */
    public synchronized boolean completeTermination(Termination termination) {
        Objects.requireNonNull(termination, "termination");
        SessionInfo session = termination.getSession();
        return sessionByPlayerId.remove(
                session.getPlayer().getPlayerId(), session.getSessionId());
    }

    /**
     * Immediately removes a session for compatibility with non-lifecycle callers.
     * Production logout uses the explicit begin and complete operations around cleanup.
     *
     * @param sessionId session to remove
     * @return removed session information when present
     */
    public synchronized Optional<SessionInfo> remove(UUID sessionId) {
        Optional<Termination> termination = beginTermination(sessionId);
        termination.ifPresent(this::completeTermination);
        return termination.map(Termination::getSession);
    }

    /**
     * Reports whether a player currently owns a live server session.
     *
     * @param playerId the server-owned player identifier
     * @return true when a reverse session mapping exists
     */
    public boolean hasPlayer(UUID playerId) {
        return playerId != null && sessionByPlayerId.containsKey(playerId);
    }

    /**
     * Generates a session identifier that is not already present in the registry.
     *
     * @return a unique session identifier
     */
    private UUID nextUniqueSessionId() {
        UUID sessionId;
        do {
            sessionId = Objects.requireNonNull(sessionIdSupplier.get(), "generated sessionId");
        } while (sessionsById.containsKey(sessionId)
                || sessionByPlayerId.containsValue(sessionId));
        return sessionId;
    }

    /**
     * Generates a guest player identifier that is not already present in the reverse index.
     *
     * @return a unique temporary player identifier
     */
    private UUID nextUniquePlayerId() {
        UUID playerId;
        do {
            playerId = Objects.requireNonNull(playerIdSupplier.get(), "generated playerId");
        } while (sessionByPlayerId.containsKey(playerId)
                || persistentIdentityLookup.exists(playerId));
        return playerId;
    }

    /**
     * Carries the invalidated session through external cleanup while its identity stays reserved.
     */
    public static final class Termination {
        private final SessionInfo session;

        /**
         * Captures the session invalidated at termination start.
         *
         * @param session invalidated session information
         */
        private Termination(SessionInfo session) {
            this.session = Objects.requireNonNull(session, "session");
        }

        /**
         * Returns the invalidated session whose resources still require cleanup.
         *
         * @return invalidated session information
         */
        public SessionInfo getSession() {
            return session;
        }
    }
}
