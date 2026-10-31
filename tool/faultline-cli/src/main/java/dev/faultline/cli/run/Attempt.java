package dev.faultline.cli.run;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.app.AppProcess;
import dev.faultline.cli.app.DockerStack;
import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.check.Checker;
import dev.faultline.cli.drain.Drainer;
import dev.faultline.cli.fault.Faults;
import dev.faultline.cli.fault.ProcessFaults;
import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.history.HistoryEntry.Status;
import dev.faultline.cli.history.HistoryLog;
import dev.faultline.cli.kafka.KafkaLag;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.traffic.TrafficSender;
import dev.faultline.cli.workload.EffectsTable;
import dev.faultline.cli.workload.Workload;

/**
 * One run of a plan in its own folder: start the app (a jar, Docker Compose services, or one already running),
 * send the traffic while the fault fires, wait until the app has finished, and check every request's effect.
 */
final class Attempt {
    static final String RESULT_FILE = "attempt.json";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final int SHOW_OFFENDERS = 10;

    private final String name;
    private final String kind;
    private final Plan plan;
    private final Path dir;
    private final Path agentJar;
    private final PrintWriter out;
    private AppProcess process;
    private DockerStack stack;

    private Attempt(String name, String kind, Plan plan, Path dir, Path agentJar, PrintWriter out) {
        this.name = name;
        this.kind = kind;
        this.plan = plan;
        this.dir = dir;
        this.agentJar = agentJar;
        this.out = out;
    }

    static AttemptResult run(String name, String kind, Plan plan, Path dir, Path agentJar, PrintWriter out) throws Exception {
        return new Attempt(name, kind, plan, dir, agentJar, out).run();
    }

    private AttemptResult run() throws Exception {
        Files.createDirectories(dir);
        var traffic = plan.traffic();
        var request = traffic.request();
        var requests = Workload.generate(plan.template(), plan.correlateBy().requestField(), traffic.seed(), traffic.count());
        var effects = new EffectsTable(plan.correlateBy().table(), plan.correlateBy().column());
        var db = plan.database();
        var fault = plan.fault();

        try (var conn = DriverManager.getConnection(db.url(), db.user(), db.password());
             var sender = new TrafficSender(URI.create(plan.target().url()));
             var lag = new KafkaLag(plan.kafka().bootstrap(), plan.kafka().group(), plan.kafka().topic())) {
            try {
                prepare(sender, lag);
                reset(conn);
                startApp(sender, lag);

                ProcessFaults processFaults = fault != null && !fault.inCode()
                    ? new ProcessFaults(fault, stack, dir, line -> Out.line(out, "%s", line)) : null;
                Out.line(out, "send      %,d x %s %s at %d/s, seed %d", traffic.count(), request.method(), request.path(), traffic.rate(), traffic.seed());
                Path historyFile = dir.resolve("history.jsonl");
                long sendMillis;
                try (var history = HistoryLog.create(historyFile)) {
                    if (processFaults != null) processFaults.start();
                    sendMillis = sender.send(requests, request.method(), request.path(), traffic.rate(), history).toMillis();
                    if (processFaults != null) processFaults.awaitDone();
                } finally {
                    if (processFaults != null) processFaults.close();
                }
                List<HistoryEntry> entries = HistoryLog.read(historyFile);
                Map<Status, Long> byStatus = entries.stream().collect(groupingBy(HistoryEntry::status, counting()));
                var sent = new AttemptResult.Sent(byStatus.getOrDefault(Status.OK, 0L), byStatus.getOrDefault(Status.FAIL, 0L),
                    byStatus.getOrDefault(Status.INFO, 0L), sendMillis);
                Out.line(out, "          done in %.1fs   ok %d, fail %d, info %d", sendMillis / 1000.0, sent.ok(), sent.fail(), sent.info());

                Out.line(out, "drain     waiting until Kafka lag is 0 and %s stops changing ...", effects.table());
                var drained = Drainer.waitUntilDone(() -> {
                    if (process != null) process.checkHealthy();
                    return effects.rows(conn);
                }, lag::lag);
                var drain = new AttemptResult.Drain(drained.count(), drained.backlog(), drained.settled(), drained.waited().toMillis());
                if (drained.settled()) {
                    Out.line(out, "          %,d rows, lag 0, unchanged for %ds", drained.count(), Drainer.QUIET.toSeconds());
                } else {
                    Out.line(out, "          gave up after %ds with lag %d and %,d rows: 'lost' results may be false",
                        Drainer.MAX_WAIT.toSeconds(), drained.backlog(), drained.count());
                }

                Integer restarts = process != null ? Integer.valueOf(process.restarts()) : stack != null ? Integer.valueOf(stack.restarts()) : null;
                closeApp();

                var checks = new ArrayList<CheckResult>();
                for (var c : Checker.check(entries, effects.appliedCounts(conn))) {
                    if (plan.checks().contains(c.name())) checks.add(c);
                }
                Integer fired = null;
                if (fault != null) {
                    fired = Faults.fired(dir);
                    Out.line(out, "faults    %d of %d fired (faults.jsonl)%s", fired, fault.count(),
                        restarts == null ? "" : ", app restarted %d times".formatted(restarts));
                    if (plan.checks().contains(Plan.FAULTS_FIRED)) checks.add(Checker.faultsFired(fault.count(), fired));
                }
                printChecks(checks);
                var result = new AttemptResult(name, kind, sent, drain, restarts, fired, checks);
                Json.writePretty(dir.resolve(RESULT_FILE), result);
                return result;
            } finally {
                closeApp();
            }
        }
    }

    /** Checks Kafka answers, then makes sure no messages from an earlier run will be processed during this one. */
    private void prepare(TrafficSender sender, KafkaLag lag) throws Exception {
        long backlog;
        try {
            backlog = lag.lag();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the consumer lag from Kafka at " + plan.kafka().bootstrap(), e);
        }
        var target = plan.target();
        if (target.compose() != null) {
            stack = new DockerStack(target.compose(), dir);
            stack.removeLeftovers();
        } else if (target.jar() != null && sender.isReachable()) {
            throw new IllegalStateException("something is already answering at " + target.url()
                + ": stop it, or remove target.jar from the plan to test the app that is running");
        }
        if (target.compose() != null || target.jar() != null) {
            long skipped = lag.skipBacklog();
            if (skipped > 0) Out.line(out, "kafka     skipped %,d leftover messages from an earlier run", skipped);
        } else {
            sender.checkReachable();
            if (backlog > 0) {
                Out.line(out, "kafka     waiting for the app to finish %,d leftover messages from an earlier run ...", backlog);
                var settled = Drainer.waitUntilDone(() -> 0, lag::lag);
                if (!settled.settled()) throw new IllegalStateException("the app still has " + settled.backlog() + " leftover messages");
            }
        }
    }

    private void reset(Connection conn) throws Exception {
        var reset = EffectsTable.reset(conn, plan.resetStatements());
        if (!reset.skipped().isEmpty() && reset.ran() == 0) {
            Out.line(out, "reset     fresh database: the app creates its tables when it starts");
        } else {
            Out.line(out, "reset     %d statements%s", reset.ran(),
                reset.skipped().isEmpty() ? "" : " (%d skipped: table not created yet)".formatted(reset.skipped().size()));
        }
    }

    private void startApp(TrafficSender sender, KafkaLag lag) throws Exception {
        var target = plan.target();
        var fault = plan.fault();
        boolean agent = fault != null && fault.inCode();
        if (agent && !Files.isRegularFile(agentJar)) {
            throw new IllegalStateException("fault agent not found at " + agentJar + ": build it with (cd tool && ./mvnw -q package -DskipTests)");
        }

        if (target.compose() != null) {
            var env = agent ? Faults.writeAgentPlan(fault, dir, DockerStack.RUN_DIR_IN_CONTAINER) : Map.<String, String>of();
            Out.line(out, "app       starting %s with Docker Compose%s ...", String.join(", ", target.compose().services()),
                agent ? ", the fault agent on " + fault.target() : "");
            long start = System.nanoTime();
            stack.up(plan.appSettings(), agent ? fault.target() : null, agentJar, env);
            int consumers = target.compose().consumers() == null ? 0 : target.compose().consumers();
            while (!sender.isReachable() || consumers > 0 && lag.stableMembers() < consumers) {
                waitOrGiveUp(start);
            }
            Out.line(out, "          ready after %.1fs%s", (System.nanoTime() - start) / 1e9, consumers > 0 ? ", " + consumers + " consumers in the group" : "");
        } else if (target.jar() != null) {
            Path appJar = Path.of(target.jar());
            if (!Files.isRegularFile(appJar)) {
                throw new IllegalStateException("app jar not found: " + Out.path(appJar) + " (build the app first, for example ./mvnw -q package -DskipTests)");
            }
            var env = agent ? Faults.writeAgentPlan(fault, dir, dir.toAbsolutePath().toString()) : Map.<String, String>of();
            process = new AppProcess(AppProcess.command(appJar, agent ? agentJar : null, target.args()), env, dir.resolve("app.log"),
                line -> Out.line(out, "%s", line));
            process.start();
            Out.line(out, "app       started %s (pid %d)%s, waiting for it to answer ...", appJar.getFileName(), process.pid(),
                agent ? " with the fault agent" : "");
            long start = System.nanoTime();
            while (!sender.isReachable()) {
                process.checkHealthy();
                waitOrGiveUp(start);
            }
            Out.line(out, "          ready after %.1fs", (System.nanoTime() - start) / 1e9);
        }
    }

    private void waitOrGiveUp(long start) throws InterruptedException {
        if (System.nanoTime() - start > STARTUP_TIMEOUT.toNanos()) {
            throw new IllegalStateException("the app was not ready within " + STARTUP_TIMEOUT.toSeconds() + "s (see app.log)");
        }
        Thread.sleep(500);
    }

    private void closeApp() {
        if (process != null) process.close();
        if (stack != null) stack.close();
        process = null;
        stack = null;
    }

    private void printChecks(List<CheckResult> checks) {
        boolean first = true;
        for (var c : checks) {
            Out.line(out, "%-9s %-14s %s", first ? "check" : "", c.name(), c.passed() ? "PASS" : "FAIL (" + c.offenders().size() + ")");
            for (var offender : c.offenders().stream().limit(SHOW_OFFENDERS).toList()) {
                Out.line(out, "%-24s %s", "", offender);
            }
            if (c.offenders().size() > SHOW_OFFENDERS) {
                Out.line(out, "%-24s ... and %d more (see %s)", "", c.offenders().size() - SHOW_OFFENDERS, RESULT_FILE);
            }
            first = false;
        }
    }
}
