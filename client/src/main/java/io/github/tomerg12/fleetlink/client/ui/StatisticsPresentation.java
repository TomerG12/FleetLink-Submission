package io.github.tomerg12.fleetlink.client.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.MatchOutcome;

/** Formats authoritative statistics values for the JavaFX dashboard without changing semantics. */
public final class StatisticsPresentation {
    private static final DateTimeFormatter COMPLETION_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    /** Prevents construction because the presentation helpers are stateless. */
    private StatisticsPresentation() {
    }

    /**
     * Formats a protocol ratio as a percentage without recomputing its source counts.
     *
     * @param ratio authoritative ratio from zero through one
     * @return percentage with one fractional digit
     */
    public static String percentage(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100.0);
    }

    /**
     * Formats a server-derived average with two fractional digits.
     *
     * @param average authoritative average
     * @return fixed-width decimal text
     */
    public static String average(double average) {
        return String.format(Locale.ROOT, "%.2f", average);
    }

    /**
     * Formats elapsed match time as hours, minutes, and seconds when needed.
     *
     * @param duration authoritative nonnegative match duration
     * @return compact duration text
     */
    public static String duration(Duration duration) {
        long seconds = Objects.requireNonNull(duration, "duration").getSeconds();
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remainder = seconds % 60;
        return hours == 0
                ? String.format(Locale.ROOT, "%d:%02d", minutes, remainder)
                : String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder);
    }

    /**
     * Formats the authoritative completion instant in a stable UTC representation.
     *
     * @param completedAt authoritative completion instant
     * @return UTC completion text
     */
    public static String completedAt(Instant completedAt) {
        return COMPLETION_FORMAT.format(Objects.requireNonNull(completedAt, "completedAt"));
    }

    /**
     * Formats a signed rating delta while preserving zero without a sign.
     *
     * @param delta authoritative persistent rating delta
     * @return signed display text
     */
    public static String ratingDelta(int delta) {
        return delta > 0 ? "+" + delta : Integer.toString(delta);
    }

    /**
     * Converts the authoritative match outcome to compact player-facing text.
     *
     * @param outcome authoritative player-oriented outcome
     * @return uppercase outcome label
     */
    public static String outcome(MatchOutcome outcome) {
        return Objects.requireNonNull(outcome, "outcome").name();
    }

    /**
     * Converts an authoritative terminal reason to readable words without changing its meaning.
     *
     * @param reason authoritative game-end reason
     * @return title-style reason label
     */
    public static String endReason(GameEndReason reason) {
        String[] words = Objects.requireNonNull(reason, "reason").name().split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return label.toString();
    }

    /**
     * Marks guest opponents explicitly while preserving the captured display name.
     *
     * @param displayName captured opponent display name
     * @param guest whether the opponent used a guest identity
     * @return opponent label
     */
    public static String opponent(String displayName, boolean guest) {
        Objects.requireNonNull(displayName, "displayName");
        return guest ? displayName + " (Guest)" : displayName;
    }
}
