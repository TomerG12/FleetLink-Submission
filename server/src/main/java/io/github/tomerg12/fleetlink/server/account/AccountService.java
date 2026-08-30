package io.github.tomerg12.fleetlink.server.account;

import io.github.tomerg12.fleetlink.server.persistence.PlayerEntity;
import io.github.tomerg12.fleetlink.server.persistence.PlayerRepository;
import io.github.tomerg12.fleetlink.server.persistence.UsernameUnavailableException;
import io.github.tomerg12.fleetlink.server.rating.EloRatingCalculator;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Coordinates registered account persistence, password verification, session claims, and callbacks.
 */
public final class AccountService {

    /** The initial stored rating for every registered account. */
    public static final int INITIAL_RATING = EloRatingCalculator.INITIAL_RATING;

    private final PlayerRepository players;
    private final SessionRegistry sessions;
    private final ClientCallbackRegistry callbacks;
    private final PasswordHasher passwordHasher;
    private final Clock clock;
    private final Supplier<UUID> playerIdSupplier;
    private final RegisteredRatingRegistry ratingRegistry;

    /**
     * Creates the registered account boundary with explicit security and identity dependencies.
     *
     * @param players persistent player repository
     * @param sessions active session and reservation registry
     * @param callbacks callback registry populated only after a successful session claim
     * @param passwordHasher password hashing policy
     * @param clock account creation clock
     * @param playerIdSupplier persistent player identifier source
     * @param ratingRegistry process-live registered rating authority
     */
    public AccountService(PlayerRepository players, SessionRegistry sessions,
                          ClientCallbackRegistry callbacks, PasswordHasher passwordHasher,
                          Clock clock, Supplier<UUID> playerIdSupplier,
                          RegisteredRatingRegistry ratingRegistry) {
        this.players = Objects.requireNonNull(players, "players");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.playerIdSupplier = Objects.requireNonNull(playerIdSupplier, "playerIdSupplier");
        this.ratingRegistry = Objects.requireNonNull(ratingRegistry, "ratingRegistry");
    }

    /**
     * Validates, persists, claims, and connects one new registered account.
     *
     * @param username submitted registration username
     * @param password exact submitted password sequence
     * @param callback exported client callback
     * @return successful registered session or an expected contract-safe failure
     */
    public SessionResult register(String username, String password,
                                  FleetLinkClientCallback callback) {
        if (callback == null) {
            return invalid("Registration requires a callback");
        }
        final UsernameIdentity identity;
        final PasswordDigest digest;
        try {
            identity = UsernameIdentity.from(username);
            digest = passwordHasher.hash(password);
        } catch (NullPointerException | IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }

        synchronized (sessions) {
            UUID playerId = nextPersistentPlayerId();
            PlayerEntity player = new PlayerEntity(playerId, identity.getDisplayName(),
                    identity.getKey(), digest.getHash(), digest.getSalt(), digest.getIterations(),
                    INITIAL_RATING, 0L, clock.instant());
            try {
                players.create(player);
            } catch (UsernameUnavailableException exception) {
                return SessionResult.failure(
                        ResultCode.USERNAME_UNAVAILABLE, "Username is unavailable");
            }
            ratingRegistry.seedIfAbsent(player.getId(), player.getRating(),
                    player.getRatingRevision());
            return establishSession(player, callback);
        }
    }

    /**
     * Authenticates exact credentials and atomically claims one registered session.
     *
     * @param username submitted login username
     * @param password exact submitted password sequence
     * @param callback exported client callback
     * @return successful registered session or an expected contract-safe failure
     */
    public SessionResult login(String username, String password,
                               FleetLinkClientCallback callback) {
        if (callback == null) {
            return invalid("Login requires a callback");
        }
        final UsernameIdentity identity;
        try {
            identity = UsernameIdentity.from(username);
            if (password == null || password.isEmpty()) {
                return invalid("Password must not be empty");
            }
        } catch (NullPointerException | IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
        Optional<PlayerEntity> found = players.findByUsernameKey(identity.getKey());
        if (found.isEmpty() || !passwordHasher.verify(password, found.get().getPasswordHash(),
                found.get().getPasswordSalt(), found.get().getPasswordIterations())) {
            return SessionResult.failure(
                    ResultCode.INVALID_CREDENTIALS, "Invalid username or password");
        }
        ratingRegistry.seedIfAbsent(found.get().getId(), found.get().getRating(),
                found.get().getRatingRevision());
        return establishSession(found.get(), callback);
    }

    /**
     * Claims a session and registers its callback only after identity ownership succeeds.
     *
     * @param entity authenticated or newly persisted player row
     * @param callback exported callback for the new session
     * @return established session or an already-active failure
     */
    private SessionResult establishSession(PlayerEntity entity,
                                           FleetLinkClientCallback callback) {
        RegisteredRatingRegistry.RatingState live = ratingRegistry.current(entity.getId());
        PlayerView player = toView(entity, live.getRating());
        Optional<SessionInfo> claimed = sessions.claimRegistered(player);
        if (claimed.isEmpty()) {
            return invalid("Account already has an active or terminating session");
        }
        callbacks.register(player, callback);
        return SessionResult.success(claimed.get());
    }

    /**
     * Generates a persistent UUID that does not collide with any active or reserved identity.
     *
     * @return unique persistent player identifier
     */
    private UUID nextPersistentPlayerId() {
        UUID playerId;
        do {
            playerId = Objects.requireNonNull(playerIdSupplier.get(), "generated playerId");
        } while (sessions.hasPlayer(playerId) || players.existsById(playerId));
        return playerId;
    }

    /**
     * Converts a detached persistent entity to the transport-safe registered player view.
     *
     * @param entity persistent player entity
     * @param liveRating process-live authoritative rating
     * @return safe registered player view
     */
    private static PlayerView toView(PlayerEntity entity, int liveRating) {
        return new PlayerView(entity.getId(), entity.getUsername(), liveRating, false);
    }

    /**
     * Creates a stable invalid-request session result.
     *
     * @param message non-blank failure detail
     * @return invalid request result
     */
    private static SessionResult invalid(String message) {
        String safeMessage = message == null || message.isBlank()
                ? "Invalid account request" : message;
        return SessionResult.failure(ResultCode.INVALID_REQUEST, safeMessage);
    }
}
