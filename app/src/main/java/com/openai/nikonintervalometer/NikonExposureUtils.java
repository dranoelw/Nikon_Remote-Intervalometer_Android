package com.openai.nikonintervalometer;

import java.util.Locale;

final class NikonExposureUtils {
    static final long BULB = 0xFFFFFFFFL;
    static final long TIME = 0xFFFFFFFDL;

    private NikonExposureUtils() {}

    static boolean isBulb(long value) { return value == BULB; }
    static boolean isTime(long value) { return value == TIME; }
    static boolean isSpecial(long value) { return isBulb(value) || isTime(value); }

    static double seconds(long value) {
        if (isSpecial(value)) return Double.NaN;
        return value / 10000.0;
    }

    static String formatIso(int iso) {
        if (iso == 0 || iso == 0xFFFF) return "Auto";
        return "ISO " + iso;
    }

    static String formatExposure(long value) {
        if (isBulb(value)) return "Bulb";
        if (isTime(value)) return "Time";

        double seconds = seconds(value);
        if (seconds <= 0.0) return "Unknown";

        if (seconds >= 1.0) {
            if (Math.abs(seconds - Math.rint(seconds)) < 0.0001) {
                return String.format(Locale.US, "%.0f s", seconds);
            }
            if (seconds >= 10.0) {
                return String.format(Locale.US, "%.1f s", seconds);
            }
            return String.format(Locale.US, "%.2f", seconds)
                    .replaceAll("0+$", "")
                    .replaceAll("\\.$", "") + " s";
        }

        double reciprocal = 1.0 / seconds;
        int[] standard = {
                2, 3, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80,
                100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
                1600, 2000, 2500, 3200, 4000
        };

        int nearest = standard[0];
        double bestError = Math.abs(Math.log(reciprocal / nearest));
        for (int candidate : standard) {
            double error = Math.abs(Math.log(reciprocal / candidate));
            if (error < bestError) {
                bestError = error;
                nearest = candidate;
            }
        }

        if (reciprocal >= 3200.0) return "1/" + nearest + " s";
        if (bestError < 0.08) return "1/" + nearest + " s";

        long rounded = Math.round(reciprocal);
        if (Math.abs(reciprocal - rounded) < 0.05) return "1/" + rounded + " s";
        return String.format(Locale.US, "1/%.1f s", reciprocal);
    }
}
