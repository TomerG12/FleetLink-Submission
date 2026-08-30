package io.github.tomerg12.fleetlink.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.persistence.FleetLinkPersistence;
import io.github.tomerg12.fleetlink.server.persistence.PersistenceTestSupport;
import io.github.tomerg12.fleetlink.server.persistence.PlayerEntity;
import io.github.tomerg12.fleetlink.server.persistence.PlayerRepository;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies account persistence, case-insensitive identity, exact passwords, and session claims.
 */
class AccountServiceTest {

    private static final FleetLinkClientCallback CALLBACK = new NoOpCallback();

    /**
     * Persists a registered player with stripped display case and no plaintext credential storage.
     */
    @Test
    void registrationPersistsSafeAccountData() {
        try (Fixture fixture = new Fixture()) {
            SessionResult result = fixture.accounts.register("  Tomer_12  ", " secret ", CALLBACK);
            PlayerEntity stored = fixture.players.findByUsernameKey("tomer_12").orElseThrow();

            assertTrue(result.isSuccess());
            assertFalse(result.getSessionInfo().getPlayer().isGuest());
            assertEquals("Tomer_12", stored.getUsername());
            assertEquals(1000, stored.getRating());
            assertEquals(0L, stored.getRatingRevision());
            assertEquals(1000, fixture.ratings.current(stored.getId()).getRating());
            assertEquals(31, stored.getPasswordIterations());
            assertNotEquals(" secret ", new String(stored.getPasswordHash(), StandardCharsets.UTF_8));
            assertFalse(arrayContains(stored.getPasswordHash(), " secret ".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Uses the database uniqueness key for exact and case-only duplicate registration.
     */
    @Test
    void duplicateUsernameIsUnavailableRegardlessOfCase() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.accounts.register("Tomer", "first", CALLBACK).isSuccess());
            fixture.endCurrent("tomer");

            assertEquals(ResultCode.USERNAME_UNAVAILABLE,
                    fixture.accounts.register("Tomer", "other", CALLBACK).getResultCode());
            assertEquals(ResultCode.USERNAME_UNAVAILABLE,
                    fixture.accounts.register("TOMER", "other", CALLBACK).getResultCode());
            assertEquals(1, fixture.players.count());
        }
    }

    /**
     * Allows only one concurrent duplicate registration to create an account.
     *
     * @throws Exception if concurrent test execution fails
     */
    @Test
    void concurrentDuplicateRegistrationCreatesOneAccount() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Callable<SessionResult> first = () -> {
                    start.await();
                    return fixture.accounts.register("Concurrent", "password", CALLBACK);
                };
                Callable<SessionResult> second = () -> {
                    start.await();
                    return fixture.accounts.register("CONCURRENT", "password", CALLBACK);
                };
                List<Future<SessionResult>> results = List.of(
                        executor.submit(first), executor.submit(second));
                start.countDown();
                long successes = results.stream().map(AccountServiceTest::get)
                        .filter(SessionResult::isSuccess).count();

                assertEquals(1, successes);
                assertEquals(1, fixture.players.count());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Rejects invalid input and a null callback before creating persistent account data.
     */
    @Test
    void structuralValidationAndCallbackPrecedePersistence() {
        try (Fixture fixture = new Fixture()) {
            assertEquals(ResultCode.INVALID_REQUEST,
                    fixture.accounts.register("x", "password", CALLBACK).getResultCode());
            assertEquals(ResultCode.INVALID_REQUEST,
                    fixture.accounts.register("ValidName", "password", null).getResultCode());
            assertEquals(ResultCode.INVALID_REQUEST,
                    fixture.accounts.register("ValidName", "", CALLBACK).getResultCode());
            assertEquals(0, fixture.players.count());
        }
    }

    /**
     * Authenticates case-insensitively while preserving exact password spaces and persistent UUID.
     */
    @Test
    void loginUsesStableIdentityAndExactPassword() {
        try (Fixture fixture = new Fixture()) {
            SessionResult registered = fixture.accounts.register(
                    "CaseUser", " exact password ", CALLBACK);
            UUID playerId = registered.getSessionInfo().getPlayer().getPlayerId();
            fixture.endCurrent("caseuser");

            assertEquals(ResultCode.INVALID_CREDENTIALS,
                    fixture.accounts.login("CASEUSER", "exact password", CALLBACK).getResultCode());
            SessionResult login = fixture.accounts.login(
                    " caseuser ", " exact password ", CALLBACK);
            assertTrue(login.isSuccess());
            assertEquals(playerId, login.getSessionInfo().getPlayer().getPlayerId());
        }
    }

    /**
     * Keeps a newer process-live rating across logout and a login that observes stale durable data.
     */
    @Test
    void loginDoesNotOverwritePendingLiveRatingFromDatabase() {
        try (Fixture fixture = new Fixture()) {
            SessionResult registered = fixture.accounts.register(
                    "LiveRating", "password", CALLBACK);
            PlayerView player = registered.getSessionInfo().getPlayer();
            PlayerView opponent = new PlayerView(UUID.randomUUID(), "Opponent", 1000, false);
            fixture.ratings.seedIfAbsent(opponent.getPlayerId(), 1000, 0L);
            fixture.ratings.applyRatedGame(
                    UUID.randomUUID(), player, opponent, player.getPlayerId());

            fixture.endCurrent("liverating");
            SessionResult login = fixture.accounts.login("LiveRating", "password", CALLBACK);

            assertTrue(login.isSuccess());
            assertEquals(1016, login.getSessionInfo().getPlayer().getRating());
            assertEquals(1000, fixture.players.findById(player.getPlayerId()).orElseThrow()
                    .getRating());
            assertEquals(1L, fixture.ratings.current(player.getPlayerId()).getRevision());
        }
    }

    /**
     * Rejects a second active login and permits login only after two-phase termination completes.
     */
    @Test
    void registeredIdentityRemainsReservedUntilTerminationCompletes() {
        try (Fixture fixture = new Fixture()) {
            SessionResult first = fixture.accounts.register("ActiveUser", "password", CALLBACK);
            UUID oldSession = first.getSessionInfo().getSessionId();
            SessionRegistry.Termination termination = fixture.sessions
                    .beginTermination(oldSession).orElseThrow();

            assertTrue(fixture.sessions.resolvePlayer(oldSession).isEmpty());
            assertEquals(ResultCode.INVALID_REQUEST,
                    fixture.accounts.login("activeuser", "password", CALLBACK).getResultCode());
            fixture.callbacks.unregister(
                    termination.getSession().getPlayer().getPlayerId(), CALLBACK);
            assertTrue(fixture.sessions.completeTermination(termination));
            assertTrue(fixture.accounts.login("ACTIVEUSER", "password", CALLBACK).isSuccess());
            assertEquals(1, fixture.players.count());
        }
    }

    /**
     * Allows exactly one of two concurrent valid logins to claim the persistent identity.
     *
     * @throws Exception if concurrent execution fails
     */
    @Test
    void concurrentValidLoginsCreateAtMostOneActiveSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.accounts.register("LoginRace", "password", CALLBACK);
            fixture.endCurrent("loginrace");
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Callable<SessionResult> login = () -> {
                    start.await();
                    return fixture.accounts.login("LOGINRACE", "password", CALLBACK);
                };
                Future<SessionResult> first = executor.submit(login);
                Future<SessionResult> second = executor.submit(login);
                start.countDown();

                assertEquals(1, List.of(get(first), get(second)).stream()
                        .filter(SessionResult::isSuccess).count());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Preserves the account and stable UUID across a file-backed persistence restart.
     *
     * @param directory temporary test directory
     */
    @Test
    void accountSurvivesPersistenceRestart(@TempDir Path directory) {
        String url = "jdbc:h2:file:" + directory.resolve("fleetlink").toAbsolutePath();
        UUID playerId;
        try (FleetLinkPersistence first = PersistenceTestSupport.open(url, "create")) {
            Fixture fixture = new Fixture(first, false);
            SessionResult registered = fixture.accounts.register(
                    "RestartUser", "password", CALLBACK);
            playerId = registered.getSessionInfo().getPlayer().getPlayerId();
            try (jakarta.persistence.EntityManager entityManager =
                         first.getEntityManagerFactory().createEntityManager()) {
                entityManager.getTransaction().begin();
                PlayerEntity durable = entityManager.find(PlayerEntity.class, playerId);
                durable.applyRatingTransition(1000, 0L, 16);
                entityManager.getTransaction().commit();
            }
        }
        try (FleetLinkPersistence second = PersistenceTestSupport.open(url, "update")) {
            Fixture fixture = new Fixture(second, false);
            SessionResult login = fixture.accounts.login("restartuser", "password", CALLBACK);
            assertTrue(login.isSuccess());
            assertEquals(playerId, login.getSessionInfo().getPlayer().getPlayerId());
            assertEquals(1016, login.getSessionInfo().getPlayer().getRating());
            assertEquals(1L, fixture.ratings.current(playerId).getRevision());
            assertEquals(1, fixture.players.count());
        }
    }

    /**
     * Finds a byte sequence within another byte sequence for plaintext persistence checks.
     *
     * @param haystack stored bytes
     * @param needle forbidden plaintext bytes
     * @return true when the exact sequence occurs
     */
    private static boolean arrayContains(byte[] haystack, byte[] needle) {
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            if (java.util.Arrays.equals(
                    java.util.Arrays.copyOfRange(haystack, start, start + needle.length), needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves one concurrent result while converting checked failures to test failures.
     *
     * @param future concurrent result future
     * @return session result
     */
    private static SessionResult get(Future<SessionResult> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Owns one isolated account service and its persistence lifecycle.
     */
    private static final class Fixture implements AutoCloseable {
        private final FleetLinkPersistence persistence;
        private final boolean ownsPersistence;
        private final PlayerRepository players;
        private final ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        private final SessionRegistry sessions;
        private final RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        private final AccountService accounts;

        /**
         * Creates a unique in-memory account service fixture.
         */
        private Fixture() {
            this(PersistenceTestSupport.openMemory(), true);
        }

        /**
         * Creates a fixture over a caller-owned or fixture-owned persistence lifecycle.
         *
         * @param persistence open persistence owner
         * @param ownsPersistence whether close should close persistence
         */
        private Fixture(FleetLinkPersistence persistence, boolean ownsPersistence) {
            this.persistence = persistence;
            this.ownsPersistence = ownsPersistence;
            players = new PlayerRepository(persistence.getEntityManagerFactory());
            sessions = new SessionRegistry(players::existsById);
            accounts = new AccountService(players, sessions, callbacks,
                    PasswordHasher.forTesting(31, new SecureRandom()), Clock.systemUTC(),
                    UUID::randomUUID, ratings);
        }

        /**
         * Completes termination for the currently active account session.
         *
         * @param username normalized or display username
         */
        private void endCurrent(String username) {
            PlayerEntity player = players.findByUsernameKey(
                    UsernameIdentity.from(username).getKey()).orElseThrow();
            UUID sessionId = sessions.findSessionByPlayerId(player.getId()).orElseThrow()
                    .getSessionId();
            SessionRegistry.Termination termination = sessions.beginTermination(sessionId)
                    .orElseThrow();
            callbacks.unregister(player.getId(), CALLBACK);
            sessions.completeTermination(termination);
        }

        /**
         * Closes fixture-owned persistence.
         */
        @Override
        public void close() {
            if (ownsPersistence) {
                persistence.close();
            }
        }
    }

    /**
     * Provides a local callback reference without behavior.
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
