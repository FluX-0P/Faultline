package dev.faultline.cli.fault;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * A fault for the agent to fire: crash on the nth hit of a point, {@code times} times across restarts.
 * The CLI hands it to the agent as a small properties file, and the agent reports back through faults.jsonl.
 */
public record FaultSpec(String point, long nthHit, int times) {
    /** Points the agent knows. before-ack: just before Spring Kafka commits the offset, after the app's DB commit. */
    public static final Set<String> POINTS = Set.of("before-ack");
    public static final String PLAN_FILE = "agent-plan.properties";
    public static final String LOG_FILE = "faults.jsonl";
    static final String PLAN_ENV = "FAULTLINE_PLAN";

    public FaultSpec {
        if (!POINTS.contains(point)) throw new IllegalArgumentException("unknown fault point '" + point + "', known: " + POINTS);
        if (nthHit < 1) throw new IllegalArgumentException("--nth-hit must be at least 1");
        if (times < 1) throw new IllegalArgumentException("--times must be at least 1");
    }

    /** Writes the agent's plan into the run folder and returns the environment the app needs to find it. */
    public Map<String, String> writeAgentPlan(Path runDir) throws IOException {
        var plan = new Properties();
        plan.setProperty("point", point);
        plan.setProperty("action", "crash");
        plan.setProperty("nth_hit", Long.toString(nthHit));
        plan.setProperty("times", Integer.toString(times));
        plan.setProperty("log", runDir.resolve(LOG_FILE).toAbsolutePath().toString());
        Path file = runDir.resolve(PLAN_FILE).toAbsolutePath();
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            plan.store(out, "written by faultline run, read by faultline-agent");
        }
        return Map.of(PLAN_ENV, file.toString());
    }

    /** How many faults actually fired, from the agent's log. */
    public static int fired(Path runDir) throws IOException {
        Path log = runDir.resolve(LOG_FILE);
        if (!Files.exists(log)) return 0;
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return (int) lines.filter(line -> !line.isBlank()).count();
        }
    }
}
