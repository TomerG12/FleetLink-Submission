package io.github.tomerg12.fleetlink.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores one registered account without exposing credentials outside the server persistence layer.
 */
@Entity
@Table(name = "players",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_players_username_key", columnNames = "username_key"),
        indexes = @Index(name = "idx_players_leaderboard",
                columnList = "rating DESC, username_key ASC, id ASC"))
public class PlayerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, length = 24)
    private String username;

    @Column(name = "username_key", nullable = false, length = 24)
    private String usernameKey;

    @Column(name = "password_hash", nullable = false, length = 32)
    private byte[] passwordHash;

    @Column(name = "password_salt", nullable = false, length = 64)
    private byte[] passwordSalt;

    @Column(name = "password_iterations", nullable = false)
    private int passwordIterations;

    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "rating_revision", nullable = false)
    private long ratingRevision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Creates an empty instance for JPA materialization.
     */
    protected PlayerEntity() {
    }

    /**
     * Creates a complete registered account row from validated account data.
     *
     * @param id stable persistent player identifier
     * @param username case-preserving stripped username
     * @param usernameKey normalized case-insensitive identity key
     * @param passwordHash derived password key
     * @param passwordSalt random password salt
     * @param passwordIterations stored PBKDF2 work factor
     * @param rating initial authoritative rating
     * @param ratingRevision durable rating revision
     * @param createdAt account creation timestamp
     */
    public PlayerEntity(UUID id, String username, String usernameKey, byte[] passwordHash,
                        byte[] passwordSalt, int passwordIterations, int rating,
                        long ratingRevision,
                        Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.username = Objects.requireNonNull(username, "username");
        this.usernameKey = Objects.requireNonNull(usernameKey, "usernameKey");
        this.passwordHash = copyRequired(passwordHash, "passwordHash");
        this.passwordSalt = copyRequired(passwordSalt, "passwordSalt");
        this.passwordIterations = passwordIterations;
        if (rating < 0 || ratingRevision < 0) {
            throw new IllegalArgumentException("rating and revision must be nonnegative");
        }
        this.rating = rating;
        this.ratingRevision = ratingRevision;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Returns the stable player identifier.
     *
     * @return player identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the case-preserving display username.
     *
     * @return stored username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the normalized username identity key.
     *
     * @return normalized key
     */
    public String getUsernameKey() {
        return usernameKey;
    }

    /**
     * Returns a defensive copy of the derived password key.
     *
     * @return password hash copy
     */
    public byte[] getPasswordHash() {
        return Arrays.copyOf(passwordHash, passwordHash.length);
    }

    /**
     * Returns a defensive copy of the password salt.
     *
     * @return password salt copy
     */
    public byte[] getPasswordSalt() {
        return Arrays.copyOf(passwordSalt, passwordSalt.length);
    }

    /**
     * Returns the PBKDF2 work factor stored with this account.
     *
     * @return password iteration count
     */
    public int getPasswordIterations() {
        return passwordIterations;
    }

    /**
     * Returns the authoritative stored rating.
     *
     * @return player rating
     */
    public int getRating() {
        return rating;
    }

    /**
     * Returns the durable rating revision.
     *
     * @return nonnegative rating revision
     */
    public long getRatingRevision() {
        return ratingRevision;
    }

    /**
     * Applies one already validated durable rating transition.
     *
     * @param expectedRating rating that must still be durable
     * @param expectedRevision revision that must still be durable
     * @param ratingDelta signed transition assigned by the terminal game
     * @throws IllegalStateException if the durable base is not the expected predecessor
     */
    public void applyRatingTransition(int expectedRating, long expectedRevision, int ratingDelta) {
        if (rating != expectedRating || ratingRevision != expectedRevision) {
            throw new IllegalStateException("rating transition base changed");
        }
        long updated = (long) rating + ratingDelta;
        if (updated < 0 || updated > Integer.MAX_VALUE || ratingRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("rating transition exceeds supported range");
        }
        rating = (int) updated;
        ratingRevision++;
    }

    /**
     * Returns the account creation timestamp.
     *
     * @return creation time
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Validates and copies sensitive byte data before storing it.
     *
     * @param value byte data to copy
     * @param name field name used in validation failures
     * @return non-empty defensive copy
     */
    private static byte[] copyRequired(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Arrays.copyOf(value, value.length);
    }
}
