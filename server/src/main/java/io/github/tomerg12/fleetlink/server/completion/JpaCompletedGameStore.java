package io.github.tomerg12.fleetlink.server.completion;

import io.github.tomerg12.fleetlink.server.persistence.CompletedGameEntity;
import io.github.tomerg12.fleetlink.server.persistence.GameParticipantEntity;
import io.github.tomerg12.fleetlink.server.persistence.PlayerEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Persists completed games and both participants atomically with strict duplicate comparison.
 */
public final class JpaCompletedGameStore implements CompletedGameStore {

    private final EntityManagerFactory entityManagerFactory;
    private final Supplier<UUID> participantIdSupplier;

    /**
     * Creates a store with random identifiers for participant rows.
     *
     * @param entityManagerFactory process-wide factory
     */
    public JpaCompletedGameStore(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, UUID::randomUUID);
    }

    /**
     * Creates a store with an explicit participant row identifier source.
     *
     * @param entityManagerFactory process-wide factory
     * @param participantIdSupplier participant row identifier source
     */
    JpaCompletedGameStore(EntityManagerFactory entityManagerFactory,
                          Supplier<UUID> participantIdSupplier) {
        this.entityManagerFactory = Objects.requireNonNull(
                entityManagerFactory, "entityManagerFactory");
        this.participantIdSupplier = Objects.requireNonNull(
                participantIdSupplier, "participantIdSupplier");
    }

    /**
     * Persists Game and both GameParticipant rows in one transaction.
     * Equivalent repeats are no-ops, while conflicting repeats are integrity failures.
     *
     * @param snapshot validated immutable completion snapshot
     * @return durable recording outcome
     */
    @Override
    public CompletionRecordOutcome record(CompletedGameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.hasRegisteredParticipant()) {
            return CompletionRecordOutcome.NOT_ELIGIBLE;
        }
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            CompletedGameEntity existing = entityManager.find(
                    CompletedGameEntity.class, snapshot.getGameId());
            if (existing != null) {
                assertEquivalent(existing, snapshot);
                transaction.commit();
                return CompletionRecordOutcome.ALREADY_RECORDED;
            }
            Map<UUID, PlayerEntity> ratedPlayers = lockRatedPlayers(entityManager, snapshot);
            if (!ratedPlayers.isEmpty()) {
                CompletedGameEntity concurrentExisting = entityManager.find(
                        CompletedGameEntity.class, snapshot.getGameId());
                if (concurrentExisting != null) {
                    assertEquivalent(concurrentExisting, snapshot);
                    transaction.commit();
                    return CompletionRecordOutcome.ALREADY_RECORDED;
                }
                validateRatingTransitions(snapshot, ratedPlayers);
            }
            CompletedGameEntity entity = buildEntity(entityManager, snapshot, ratedPlayers);
            entityManager.persist(entity);
            entityManager.flush();
            applyRatingTransitions(snapshot, ratedPlayers);
            entityManager.flush();
            transaction.commit();
            return CompletionRecordOutcome.RECORDED;
        } catch (CompletionIntegrityException exception) {
            rollback(transaction);
            throw exception;
        } catch (PersistenceException exception) {
            rollback(transaction);
            return classifyConcurrentDuplicate(snapshot, exception);
        } catch (RuntimeException exception) {
            rollback(transaction);
            throw exception;
        } finally {
            entityManager.close();
        }
    }

    /**
     * Returns a detached immutable completion snapshot for test and diagnostic inspection.
     *
     * @param gameId authoritative game identifier
     * @return stored snapshot when present
     */
    public Optional<CompletedGameSnapshot> find(UUID gameId) {
        if (gameId == null) {
            return Optional.empty();
        }
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            CompletedGameEntity entity = entityManager.find(CompletedGameEntity.class, gameId);
            return entity == null ? Optional.empty() : Optional.of(toSnapshot(entity));
        }
    }

    /**
     * Returns the number of durable completed-game rows.
     *
     * @return completed game count
     */
    public long countGames() {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.createQuery(
                    "select count(g) from CompletedGameEntity g", Long.class).getSingleResult();
        }
    }

    /**
     * Returns the number of durable participant rows.
     *
     * @return participant row count
     */
    public long countParticipants() {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.createQuery(
                    "select count(p) from GameParticipantEntity p", Long.class).getSingleResult();
        }
    }

    /**
     * Builds a complete JPA aggregate and resolves registered links inside the transaction.
     *
     * @param entityManager operation-scoped entity manager
     * @param snapshot immutable completion snapshot
     * @param ratedPlayers locked registered players participating in a rated game
     * @return complete transient game entity
     */
    private CompletedGameEntity buildEntity(EntityManager entityManager,
                                            CompletedGameSnapshot snapshot,
                                            Map<UUID, PlayerEntity> ratedPlayers) {
        CompletedGameEntity game = new CompletedGameEntity(
                snapshot.getGameId(), snapshot.getStartedAt(), snapshot.getCompletedAt(),
                snapshot.getEndReason());
        for (CompletedParticipantSnapshot participant : snapshot.getParticipants()) {
            PlayerEntity player = null;
            if (!participant.isGuest()) {
                player = ratedPlayers.get(participant.getPlayerId());
                if (player == null) {
                    player = entityManager.find(PlayerEntity.class, participant.getPlayerId());
                }
                if (player == null) {
                    throw new CompletionIntegrityException(
                            "Registered completion participant has no persistent Player row");
                }
            }
            GameParticipantEntity row = new GameParticipantEntity(
                    Objects.requireNonNull(participantIdSupplier.get(), "participant row id"),
                    player, participant.getPlayerId(), participant.getDisplayName(),
                    participant.isGuest(), participant.getRatingAtMatch(), participant.getResult(),
                    participant.getShotsFired(), participant.getHits(),
                    participant.getShipsSunk(), participant.getTurnsTaken(),
                    participant.getRatingDelta(), participant.getRatingRevisionBefore());
            game.addParticipant(row);
        }
        return game;
    }

    /**
     * Locks rated registered players in stable identity order before any transition is validated.
     *
     * @param entityManager operation-scoped entity manager
     * @param snapshot immutable completion snapshot
     * @return locked players keyed by identifier, empty for an unrated game
     */
    private static Map<UUID, PlayerEntity> lockRatedPlayers(
            EntityManager entityManager, CompletedGameSnapshot snapshot) {
        List<CompletedParticipantSnapshot> rated = snapshot.getParticipants().stream()
                .filter(participant -> participant.getRatingRevisionBefore() != null)
                .sorted(Comparator.comparing(participant -> participant.getPlayerId().toString()))
                .toList();
        Map<UUID, PlayerEntity> players = new HashMap<>();
        for (CompletedParticipantSnapshot participant : rated) {
            PlayerEntity player = entityManager.find(PlayerEntity.class,
                    participant.getPlayerId(), LockModeType.PESSIMISTIC_WRITE);
            if (player == null) {
                throw new CompletionIntegrityException(
                        "Rated completion participant has no persistent Player row");
            }
            players.put(participant.getPlayerId(), player);
        }
        return players;
    }

    /**
     * Validates all locked durable rating bases before either player row is changed.
     *
     * @param snapshot immutable completion snapshot
     * @param ratedPlayers locked player rows keyed by identifier
     */
    private static void validateRatingTransitions(
            CompletedGameSnapshot snapshot, Map<UUID, PlayerEntity> ratedPlayers) {
        for (CompletedParticipantSnapshot participant : snapshot.getParticipants()) {
            Long expectedRevision = participant.getRatingRevisionBefore();
            if (expectedRevision == null) {
                continue;
            }
            PlayerEntity player = ratedPlayers.get(participant.getPlayerId());
            if (player.getRatingRevision() < expectedRevision) {
                throw new CompletionPredecessorPendingException(
                        "Earlier rating revision is not durable for player "
                                + participant.getPlayerId());
            }
            if (player.getRatingRevision() > expectedRevision
                    || player.getRating() != participant.getRatingAtMatch()) {
                throw new CompletionIntegrityException(
                        "Durable rating base conflicts for player " + participant.getPlayerId());
            }
        }
    }

    /**
     * Applies every validated rated transition to the already locked player rows.
     *
     * @param snapshot immutable completion snapshot
     * @param ratedPlayers locked player rows keyed by identifier
     */
    private static void applyRatingTransitions(CompletedGameSnapshot snapshot,
                                               Map<UUID, PlayerEntity> ratedPlayers) {
        for (CompletedParticipantSnapshot participant : snapshot.getParticipants()) {
            Long revision = participant.getRatingRevisionBefore();
            if (revision == null) {
                continue;
            }
            PlayerEntity player = ratedPlayers.get(participant.getPlayerId());
            player.applyRatingTransition(participant.getRatingAtMatch(), revision,
                    participant.getRatingDelta());
        }
    }

    /**
     * Reloads a game after a constraint race and distinguishes equivalence from conflict.
     *
     * @param snapshot requested completion data
     * @param cause original constraint or persistence failure
     * @return equivalent duplicate outcome
     */
    private CompletionRecordOutcome classifyConcurrentDuplicate(
            CompletedGameSnapshot snapshot, PersistenceException cause) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            CompletedGameEntity existing = entityManager.find(
                    CompletedGameEntity.class, snapshot.getGameId());
            if (existing == null) {
                throw cause;
            }
            try {
                assertEquivalent(existing, snapshot);
                return CompletionRecordOutcome.ALREADY_RECORDED;
            } catch (CompletionIntegrityException conflict) {
                throw new CompletionIntegrityException(
                        "Conflicting completion data exists for game " + snapshot.getGameId(), cause);
            }
        }
    }

    /**
     * Rejects an existing row unless every required persisted invariant is equivalent.
     *
     * @param existing existing persistent aggregate
     * @param requested requested immutable aggregate
     */
    private static void assertEquivalent(CompletedGameEntity existing,
                                         CompletedGameSnapshot requested) {
        CompletedGameSnapshot stored = toSnapshot(existing);
        if (!stored.equivalentTo(requested)) {
            throw new CompletionIntegrityException(
                    "Conflicting completion data exists for game " + requested.getGameId());
        }
    }

    /**
     * Converts a managed aggregate to the validated persistence-agnostic representation.
     *
     * @param entity managed completed game entity
     * @return immutable snapshot
     */
    private static CompletedGameSnapshot toSnapshot(CompletedGameEntity entity) {
        List<CompletedParticipantSnapshot> participants = entity.getParticipants().stream()
                .map(JpaCompletedGameStore::toParticipant)
                .sorted(Comparator.comparing(participant -> participant.getPlayerId().toString()))
                .toList();
        UUID winnerId = participants.stream()
                .filter(participant -> participant.getResult()
                        == io.github.tomerg12.fleetlink.server.persistence.ParticipantResult.WIN)
                .findFirst().orElseThrow().getPlayerId();
        return new CompletedGameSnapshot(entity.getId(), entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getEndReason(), winnerId, participants);
    }

    /**
     * Converts one managed participant row to an immutable snapshot.
     *
     * @param entity managed participant row
     * @return immutable participant snapshot
     */
    private static CompletedParticipantSnapshot toParticipant(GameParticipantEntity entity) {
        return new CompletedParticipantSnapshot(entity.getPlayerIdSnapshot(),
                entity.getDisplayNameSnapshot(), entity.isGuest(), entity.getRatingAtMatch(),
                entity.getResult(), entity.getShotsFired(), entity.getHits(),
                entity.getShipsSunk(), entity.getTurnsTaken(), entity.getRatingDelta(),
                entity.getRatingRevisionBefore());
    }

    /**
     * Rolls back an active transaction before the operation EntityManager is closed.
     *
     * @param transaction transaction that may be active
     */
    private static void rollback(EntityTransaction transaction) {
        if (transaction.isActive()) {
            transaction.rollback();
        }
    }
}
