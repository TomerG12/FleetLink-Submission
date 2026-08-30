package io.github.tomerg12.fleetlink.server.account;

import java.util.Arrays;
import java.util.Objects;

/**
 * Carries a derived password key, random salt, and the work factor used to create it.
 */
public final class PasswordDigest {

    private final byte[] hash;
    private final byte[] salt;
    private final int iterations;

    /**
     * Creates an immutable password digest value.
     *
     * @param hash derived key bytes
     * @param salt random salt bytes
     * @param iterations PBKDF2 work factor
     */
    PasswordDigest(byte[] hash, byte[] salt, int iterations) {
        this.hash = copyRequired(hash, "hash");
        this.salt = copyRequired(salt, "salt");
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        this.iterations = iterations;
    }

    /**
     * Returns a defensive copy of the derived key.
     *
     * @return derived key copy
     */
    public byte[] getHash() {
        return Arrays.copyOf(hash, hash.length);
    }

    /**
     * Returns a defensive copy of the random salt.
     *
     * @return salt copy
     */
    public byte[] getSalt() {
        return Arrays.copyOf(salt, salt.length);
    }

    /**
     * Returns the work factor used for this digest.
     *
     * @return PBKDF2 iterations
     */
    public int getIterations() {
        return iterations;
    }

    /**
     * Copies required sensitive bytes so callers cannot mutate stored values.
     *
     * @param value source bytes
     * @param name validation field name
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
