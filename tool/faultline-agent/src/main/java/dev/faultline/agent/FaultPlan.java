package dev.faultline.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * What the agent should do. The CLI writes it as a small properties file and passes its path in
 * {@code FAULTLINE_PLAN}; plain properties keep the agent free of any parsing library.
 *
 * <pre>
 * point=before-ack
 * action=crash
 * nth_hit=300
 * times=3
 * log=/path/to/run/faults.jsonl
 * </pre>
 */
public record FaultPlan(String point, String action, long nthHit, int times, Path log) {
    static final String ENV = "FAULTLINE_PLAN";

    public FaultPlan {
        if (!action.equals("crash")) throw new IllegalArgumentException("unsupported action '" + action + "'");
        if (nthHit < 1) throw new IllegalArgumentException("nth_hit must be at least 1");
        if (times < 1) throw new IllegalArgumentException("times must be at least 1");
    }

    static FaultPlan load(Path file) throws IOException {
        var p = new Properties();
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new FaultPlan(required(p, "point"), p.getProperty("action", "crash"),
            Long.parseLong(required(p, "nth_hit")), Integer.parseInt(required(p, "times")), Path.of(required(p, "log")));
    }

    /**
     * Crashes still to come. Each crash restarts the app and the agent with it, so the count of crashes
     * already done has to come from the fault log, not from memory.
     */
    int remaining() throws IOException {
        return times - FaultLog.count(log);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("plan is missing '" + key + "'");
        return value.trim();
    }
}
