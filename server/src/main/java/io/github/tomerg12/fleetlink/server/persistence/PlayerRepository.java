package io.github.tomerg12.fleetlink.server.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Performs transaction-scoped registered-player persistence without sharing EntityManagers.
 */
public final class PlayerRepository {

    private final EntityManagerFactory entityManagerFactory;

    /**
     * Creates a repository backed by the process-wide EntityManagerFactory.
     *
     * @param entityManagerFactory factory used to create one EntityManager per operation
     */
    public PlayerRepository(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = Objects.requireNonNull(
                entityManagerFactory, "entityManagerFactory");
    }

    /**
     * Persists one complete account in its own transaction.
     *
     * @param player validated player entity
     * @throws UsernameUnavailableException when a concurrent or prior account owns the username
     * @throws PersistenceException for other persistence failures
     */
    public void create(PlayerEntity player) {
        Objects.requireNonNull(player, "player");
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            entityManager.persist(player);
            entityManager.flush();
            transaction.commit();
        } catch (PersistenceException exception) {
            rollback(transaction);
            if (findByUsernameKey(player.getUsernameKey()).isPresent()) {
                throw new UsernameUnavailableException(exception);
            }
            throw exception;
        } finally {
            entityManager.close();
        }
    }

    /**
     * Finds one registered account by its normalized case-insensitive username key.
     *
     * @param usernameKey normalized username identity key
     * @return detached player entity when present
     */
    public Optional<PlayerEntity> findByUsernameKey(String usernameKey) {
        Objects.requireNonNull(usernameKey, "usernameKey");
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.createQuery(
                            "select p from PlayerEntity p where p.usernameKey = :key",
                            PlayerEntity.class)
                    .setParameter("key", usernameKey)
                    .getResultStream()
                    .findFirst();
        }
    }

    /**
     * Finds one registered account by its stable persistent identifier.
     *
     * @param playerId persistent player identifier
     * @return detached player entity when present
     */
    public Optional<PlayerEntity> findById(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return Optional.ofNullable(entityManager.find(PlayerEntity.class, playerId));
        }
    }

    /**
     * Reports whether a persistent account owns an identifier.
     *
     * @param playerId identifier to inspect
     * @return true when a player row exists
     */
    public boolean existsById(UUID playerId) {
        return findById(playerId).isPresent();
    }

    /**
     * Returns the number of persistent registered player rows.
     *
     * @return player row count
     */
    public long count() {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.createQuery("select count(p) from PlayerEntity p", Long.class)
                    .getSingleResult();
        }
    }

    /**
     * Rolls back an active transaction while preserving the original persistence failure.
     *
     * @param transaction transaction that may be active
     */
    private static void rollback(EntityTransaction transaction) {
        if (transaction.isActive()) {
            transaction.rollback();
        }
    }
}
