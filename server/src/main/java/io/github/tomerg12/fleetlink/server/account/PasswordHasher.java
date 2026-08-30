package io.github.tomerg12.fleetlink.server.account;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hashes exact submitted password character sequences with PBKDF2WithHmacSHA256.
 */
public final class PasswordHasher {

    /** The non-overridable production PBKDF2 work factor. */
    public static final int PRODUCTION_ITERATIONS = 600_000;

    /** The production random salt size in bytes. */
    public static final int PRODUCTION_SALT_BYTES = 16;

    /** The derived key size in bits. */
    public static final int DERIVED_KEY_BITS = 256;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private final int iterations;
    private final int saltBytes;
    private final SecureRandom secureRandom;

    /**
     * Creates an explicitly configured hasher for production or isolated tests.
     *
     * @param iterations positive PBKDF2 work factor
     * @param saltBytes salt size of at least 16 bytes
     * @param secureRandom cryptographic salt source
     */
    private PasswordHasher(int iterations, int saltBytes, SecureRandom secureRandom) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        if (saltBytes < PRODUCTION_SALT_BYTES) {
            throw new IllegalArgumentException("salt must contain at least 16 bytes");
        }
        this.iterations = iterations;
        this.saltBytes = saltBytes;
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /**
     * Creates the fixed production password configuration.
     *
     * @return production PBKDF2 hasher
     */
    public static PasswordHasher production() {
        return new PasswordHasher(
                PRODUCTION_ITERATIONS, PRODUCTION_SALT_BYTES, new SecureRandom());
    }

    /**
     * Creates an explicitly low-work test hasher without changing production configuration.
     *
     * @param iterations positive test work factor
     * @param secureRandom deterministic or real test salt source
     * @return isolated test hasher
     */
    public static PasswordHasher forTesting(int iterations, SecureRandom secureRandom) {
        return new PasswordHasher(iterations, PRODUCTION_SALT_BYTES, secureRandom);
    }

    /**
     * Derives a salted key from the exact password character sequence without normalization.
     *
     * @param password exact submitted Java String
     * @return immutable derived password value
     */
    public PasswordDigest hash(String password) {
        requirePassword(password);
        byte[] salt = new byte[saltBytes];
        secureRandom.nextBytes(salt);
        return new PasswordDigest(derive(password, salt, iterations), salt, iterations);
    }

    /**
     * Verifies the exact submitted password with the stored salt and iteration count.
     *
     * @param password exact submitted Java String
     * @param expectedHash stored derived key
     * @param salt stored salt
     * @param storedIterations stored PBKDF2 work factor
     * @return true when constant-time byte comparison succeeds
     */
    public boolean verify(String password, byte[] expectedHash, byte[] salt,
                          int storedIterations) {
        requirePassword(password);
        Objects.requireNonNull(expectedHash, "expectedHash");
        Objects.requireNonNull(salt, "salt");
        if (storedIterations <= 0) {
            throw new IllegalArgumentException("storedIterations must be positive");
        }
        byte[] actual = derive(password, salt, storedIterations);
        try {
            return MessageDigest.isEqual(expectedHash, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    /**
     * Derives a key with the standard Java PBKDF2 provider and clears the temporary character copy.
     *
     * @param password exact password string
     * @param salt salt bytes
     * @param workFactor PBKDF2 iteration count
     * @return derived key bytes
     */
    private static byte[] derive(String password, byte[] salt, int workFactor) {
        PBEKeySpec specification = new PBEKeySpec(
                password.toCharArray(), salt, workFactor, DERIVED_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM)
                    .generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required PBKDF2 algorithm is unavailable", exception);
        } finally {
            specification.clearPassword();
        }
    }

    /**
     * Rejects absent or empty passwords without stripping or transforming their content.
     *
     * @param password exact submitted password
     */
    private static void requirePassword(String password) {
        Objects.requireNonNull(password, "password");
        if (password.isEmpty()) {
            throw new IllegalArgumentException("Password must not be empty");
        }
    }
}
