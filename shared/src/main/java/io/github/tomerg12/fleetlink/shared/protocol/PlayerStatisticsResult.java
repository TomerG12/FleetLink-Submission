package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Reports personal statistics success or an expected session, request, or guest failure.
 */
public final class PlayerStatisticsResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Stable machine-readable outcome for the personal statistics request. */
    private final ResultCode resultCode;
    /** Empty success text or required failure explanation. */
    private final String message;
    /** Personal statistics payload present only for success. */
    private final PlayerStatisticsView statistics;

    /**
     * Stores a validated result created by the public factories.
     *
     * @param resultCode stable machine-readable outcome
     * @param message empty success text or required failure explanation
     * @param statistics successful payload, or null for failure
     */
    private PlayerStatisticsResult(ResultCode resultCode, String message,
                                   PlayerStatisticsView statistics) {
        this.resultCode = resultCode;
        this.message = message;
        this.statistics = statistics;
    }

    /**
     * Creates a successful personal statistics result.
     *
     * @param statistics validated statistics payload
     * @return success result
     * @throws NullPointerException if statistics is null
     */
    public static PlayerStatisticsResult success(PlayerStatisticsView statistics) {
        return new PlayerStatisticsResult(ResultCode.SUCCESS, "",
                Objects.requireNonNull(statistics, "statistics"));
    }

    /**
     * Creates an expected personal statistics failure without a payload.
     *
     * @param resultCode stable non-success outcome
     * @param message non-blank player-facing explanation
     * @return validated failure result
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the code is success or the message is blank
     */
    public static PlayerStatisticsResult failure(ResultCode resultCode, String message) {
        return new PlayerStatisticsResult(ResultSupport.requireFailureCode(resultCode),
                ResultSupport.requireFailureMessage(message), null);
    }

    /**
     * Reports whether a statistics payload is present.
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
     * Returns personal statistics only after success.
     *
     * @return statistics payload, or null for failure
     */
    public PlayerStatisticsView getStatistics() {
        return statistics;
    }
}
