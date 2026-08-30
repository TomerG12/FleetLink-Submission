package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Reports a committed leaderboard or an expected session or request failure.
 */
public final class LeaderboardResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Stable machine-readable outcome for the leaderboard request. */
    private final ResultCode resultCode;
    /** Empty success text or required failure explanation. */
    private final String message;
    /** Immutable ordered entries present only for success. */
    private final List<LeaderboardEntryView> entries;

    /**
     * Stores a validated result created by the public factories.
     *
     * @param resultCode stable machine-readable outcome
     * @param message empty success text or required failure explanation
     * @param entries immutable successful payload, or null for failure
     */
    private LeaderboardResult(ResultCode resultCode, String message,
                              List<LeaderboardEntryView> entries) {
        this.resultCode = resultCode;
        this.message = message;
        this.entries = entries;
    }

    /**
     * Creates a successful leaderboard result with a defensive immutable copy.
     *
     * @param entries already ordered and ranked leaderboard entries
     * @return success result
     * @throws NullPointerException if the list or an entry is null
     */
    public static LeaderboardResult success(List<LeaderboardEntryView> entries) {
        return new LeaderboardResult(ResultCode.SUCCESS, "",
                List.copyOf(Objects.requireNonNull(entries, "entries")));
    }

    /**
     * Creates an expected leaderboard failure without a payload.
     *
     * @param resultCode stable non-success outcome
     * @param message non-blank player-facing explanation
     * @return validated failure result
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the code is success or the message is blank
     */
    public static LeaderboardResult failure(ResultCode resultCode, String message) {
        return new LeaderboardResult(ResultSupport.requireFailureCode(resultCode),
                ResultSupport.requireFailureMessage(message), null);
    }

    /**
     * Reports whether a leaderboard payload is present.
     *
     * @return true for success
     */
    public boolean isSuccess() {
        return resultCode == ResultCode.SUCCESS;
    }

    /**
     * Returns the stable outcome code.
     *
     * @return result code
     */
    public ResultCode getResultCode() {
        return resultCode;
    }

    /**
     * Returns the player-facing outcome message.
     *
     * @return empty success text or non-blank failure text
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the ordered committed leaderboard only after success.
     *
     * @return immutable entries, or null for failure
     */
    public List<LeaderboardEntryView> getEntries() {
        return entries;
    }
}
