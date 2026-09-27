package com.openai.nikonintervalometer;

import java.util.Locale;

final class SequenceTiming {
    private SequenceTiming() {}

    static long plannedTotalMs(double exposureSeconds, double pauseSeconds, int shots) {
        long exposureMs = (long)(exposureSeconds * 1000.0);
        long pauseMs = (long)(pauseSeconds * 1000.0);
        return (long)shots * exposureMs + (long)Math.max(0, shots - 1) * pauseMs;
    }

    static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, (millis + 999) / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }
}
