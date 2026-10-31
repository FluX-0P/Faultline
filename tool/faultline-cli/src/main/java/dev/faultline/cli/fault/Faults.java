package dev.faultline.cli.fault;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import dev.faultline.cli.Json;
import dev.faultline.cli.plan.Durations;
import dev.faultline.cli.plan.Plan;

/**
 * Faults and their record. A code fault goes to the agent as a small properties file; the agent and the CLI's own
 * process faults both report back through faults.jsonl, one line per fault that fired.
 */
public final class Faults {
    public static final String PLAN_FILE = "agent-plan.properties";
    public static final String PARTNER_PLAN_FILE = "agent-plan-partner.properties";
    public static final String LOG_FILE = "faults.jsonl";
    /** Once this file is in the run folder, the agent starts counting calls (written after a warm-up). */
    static final String ARM_FILE = "armed";
    static final String PLAN_ENV = "FAULTLINE_PLAN";
    /** The UDP port a barrier's holding agent listens on, inside its container, for its partners' passes. */
    static final int BARRIER_PORT = 7071;

    private Faults() {}

    /**
     * Writes the agent's plan into the run folder and returns the environment the app needs to find it.
     * {@code appRunDir} is the run folder as the app sees it: the same path for a jar, a mounted path in a container.
     * With {@code armLater}, the agent counts no calls until {@link #arm} has run, such as after a warm-up.
     */
    public static Map<String, String> writeAgentPlan(Plan.Fault fault, Path runDir, String appRunDir, boolean armLater) throws IOException {
        var plan = new Properties();
        plan.setProperty("point", fault.point());
        plan.setProperty("when", fault.when() == null ? "before" : fault.when());
        plan.setProperty("action", fault.action());
        plan.setProperty("nth_hit", Long.toString(fault.nthHit()));
        plan.setProperty("times", Integer.toString(fault.count()));
        if (fault.duration() != null) plan.setProperty("delay_ms", Long.toString(Durations.parse(fault.duration()).toMillis()));
        plan.setProperty("log", appRunDir + "/" + LOG_FILE);
        if ("barrier".equals(fault.action())) {
            plan.setProperty("until", fault.until());
            if (!fault.partnerServices().isEmpty()) plan.setProperty("listen", Integer.toString(BARRIER_PORT));
        }
        if (armLater) plan.setProperty("arm_file", appRunDir + "/" + ARM_FILE);
        return write(plan, runDir, appRunDir, PLAN_FILE);
    }

    /** Lets an agent that waits for it start counting calls. */
    public static void arm(Path runDir) throws IOException {
        Files.writeString(runDir.resolve(ARM_FILE), Instant.now().toString(), StandardCharsets.UTF_8);
    }

    /**
     * The plan for a barrier's partners: their agent fires nothing, it only tells the holding service (fault.target,
     * reached by its Compose name) each time it returns from the until point.
     */
    public static Map<String, String> writePartnerPlan(Plan.Fault fault, Path runDir, String appRunDir) throws IOException {
        var plan = new Properties();
        plan.setProperty("point", fault.until());
        plan.setProperty("when", "after");
        plan.setProperty("action", "signal");
        plan.setProperty("signal_to", fault.target() + ":" + BARRIER_PORT);
        plan.setProperty("nth_hit", "1");
        plan.setProperty("times", "1");
        plan.setProperty("log", appRunDir + "/" + LOG_FILE);
        return write(plan, runDir, appRunDir, PARTNER_PLAN_FILE);
    }

    private static Map<String, String> write(Properties plan, Path runDir, String appRunDir, String file) throws IOException {
        try (Writer out = Files.newBufferedWriter(runDir.resolve(file), StandardCharsets.UTF_8)) {
            plan.store(out, "written by faultline run, read by faultline-agent");
        }
        return Map.of(PLAN_ENV, appRunDir + "/" + file);
    }

    /** Records a fault the CLI fired itself, in the same log the agent writes. */
    public static synchronized void logFired(Path runDir, String point, String action, String target) throws IOException {
        var line = new LinkedHashMap<String, Object>();
        line.put("point", point);
        line.put("action", action);
        line.put("target", target);
        line.put("at", Instant.now().toString());
        Files.writeString(runDir.resolve(LOG_FILE), Json.MAPPER.writeValueAsString(line) + "\n", StandardCharsets.UTF_8, CREATE, APPEND);
    }

    /** How many faults actually fired. */
    public static int fired(Path runDir) throws IOException {
        Path log = runDir.resolve(LOG_FILE);
        if (!Files.exists(log)) return 0;
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return (int) lines.filter(line -> !line.isBlank()).count();
        }
    }
}
