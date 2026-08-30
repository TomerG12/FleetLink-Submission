package io.github.tomerg12.fleetlink.client.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

/**
 * Binds a presentation-only JavaFX countdown to an absolute server-authored deadline.
 * Reaching zero only changes label text; it never performs RMI, navigation, turn, strike, or winner
 * transitions.
 */
final class DeadlineCountdown {
    /** Prevents construction of the stateless countdown presentation helper. */
    private DeadlineCountdown() {
    }

    /**
     * Starts a lightweight local countdown and stops it when the label leaves its Scene.
     * The timeline is presentation-only and performs no authoritative action when zero is reached.
     *
     * @param label label receiving MM:SS text
     * @param deadlineEpochMillis authoritative deadline from GameView, or zero when inactive
     * @throws NullPointerException if label is null
     */
    static void bind(Label label, long deadlineEpochMillis) {
        if (deadlineEpochMillis <= 0L) {
            label.setText("--:--");
            return;
        }
        Runnable update = () -> label.setText(formatRemaining(deadlineEpochMillis,
                System.currentTimeMillis()));
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, event -> update.run()),
                new KeyFrame(Duration.millis(250), event -> update.run()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
        label.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                timeline.stop();
            } else {
                update.run();
                timeline.play();
            }
        });
    }

    /**
     * Formats non-negative display time without triggering any authoritative behavior at zero.
     *
     * @param deadlineEpochMillis absolute server-provided deadline
     * @param localNowMillis local presentation clock value
     * @return zero-clamped MM:SS text
     */
    static String formatRemaining(long deadlineEpochMillis, long localNowMillis) {
        long remainingMillis = Math.max(0L, deadlineEpochMillis - localNowMillis);
        long totalSeconds = (remainingMillis + 999L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }
}
