package dev.faultline.cli.plan;

import java.time.Duration;
import java.util.regex.Pattern;

/** Durations as plans write them: 500ms, 15s, 2m. */
public final class Durations {
    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m)");

    private Durations() {}

    public static Duration parse(String text) {
        var m = text == null ? null : DURATION.matcher(text.strip());
        if (m == null || !m.matches()) throw new IllegalArgumentException("'" + text + "' is not a duration such as 500ms, 15s or 2m");
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            default -> Duration.ofMinutes(n);
        };
    }

    static boolean valid(String text) {
        try {
            return !parse(text).isZero();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
