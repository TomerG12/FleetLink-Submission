package io.github.tomerg12.fleetlink.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

/**
 * Verifies the fixed production PBKDF2 policy and exact password sequence semantics.
 */
class PasswordHasherTest {

    /**
     * Keeps production at 600,000 iterations while allowing an explicit low-work test hasher.
     */
    @Test
    void productionAndTestWorkFactorsRemainSeparate() {
        assertEquals(600_000, PasswordHasher.PRODUCTION_ITERATIONS);
        PasswordHasher testHasher = PasswordHasher.forTesting(17, new SecureRandom());
        PasswordDigest digest = testHasher.hash("secret");

        assertEquals(17, digest.getIterations());
        assertEquals(16, digest.getSalt().length);
        assertEquals(32, digest.getHash().length);
        assertTrue(testHasher.verify("secret", digest.getHash(), digest.getSalt(),
                digest.getIterations()));
    }

    /**
     * Treats leading and trailing spaces as significant password characters.
     */
    @Test
    void passwordCharacterSequenceIsNeverTrimmedOrNormalized() {
        PasswordHasher hasher = PasswordHasher.forTesting(17, new SecureRandom());
        PasswordDigest digest = hasher.hash(" secret ");

        assertTrue(hasher.verify(" secret ", digest.getHash(), digest.getSalt(),
                digest.getIterations()));
        assertFalse(hasher.verify("secret", digest.getHash(), digest.getSalt(),
                digest.getIterations()));
        assertFalse(hasher.verify(" secret", digest.getHash(), digest.getSalt(),
                digest.getIterations()));
    }
}
