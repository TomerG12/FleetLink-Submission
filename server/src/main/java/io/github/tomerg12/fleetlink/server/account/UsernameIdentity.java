package io.github.tomerg12.fleetlink.server.account;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Carries a validated case-preserving username and its case-insensitive identity key.
 */
public final class UsernameIdentity {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_]{3,24}");

    private final String displayName;
    private final String key;

    /**
     * Stores a validated username pair.
     *
     * @param displayName stripped case-preserving username
     * @param key Locale.ROOT lowercase identity key
     */
    private UsernameIdentity(String displayName, String key) {
        this.displayName = displayName;
        this.key = key;
    }

    /**
     * Strips and validates a registration or login username under the T5 account policy.
     *
     * @param username submitted username
     * @return validated display and identity values
     * @throws NullPointerException if the username is null
     * @throws IllegalArgumentException if length or allowed characters are invalid
     */
    public static UsernameIdentity from(String username) {
        String stripped = Objects.requireNonNull(username, "username").strip();
        if (!ALLOWED.matcher(stripped).matches()) {
            throw new IllegalArgumentException(
                    "Username must be 3 to 24 characters using letters, digits, or underscore");
        }
        return new UsernameIdentity(stripped, stripped.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the case-preserving stripped display username.
     *
     * @return display username
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the normalized case-insensitive identity key.
     *
     * @return normalized username key
     */
    public String getKey() {
        return key;
    }
}
