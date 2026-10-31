package dev.faultline.cli.run;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.app.AppProcess;
import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.check.Checker;
import dev.faultline.cli.drain.Drainer;
import dev.faultline.cli.fault.FaultSpec;
import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.history.HistoryEntry.Status;
import dev.faultline.cli.history.HistoryLog;
import dev.faultline.cli.kafka.KafkaLag;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.report.Finding;
import dev.faultline.cli.report.Findings;
import dev.faultline.cli.report.ReportWriter;
import dev.faultline.cli.traffic.TrafficSender;
import dev.faultline.cli.workload.EffectsTable;
import dev.faultline.cli.workload.Workload;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "run", mixinStandardHelpOptions = true,
    description = {"Run an approved plan: start the app (with the fault agent when the plan has a fault), send the plan's",
        "traffic, wait for the app to finish, check every request's effect, and write a report. Same plan and seed, same requests."})
public final class RunCommand implements Callable<Integer> {
    private static final int SHOW_OFFENDERS = 10;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final DateTimeFormatter RUN_DIR_NAME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "PLAN", description = "The plan to run, such as .faultline/demo-app/plan.yaml")
    Path planFile;

    @Option(names = "--seed", description = "Seed for the generated requests. Same seed, same requests (default: the plan's, else random)")
    Long seed;

    @Option(names = "--count", description = "Requests to send (default: the plan's)")
    Integer count;

    @Option(names = "--rate", description = "Requests per second (default: the plan's)")
    Integer rate;

    @Option(names = "--app-arg", description = "An extra argument for the app, repeatable. Use the = form: --app-arg=--demo.fixes.dedupe=true")
    List<String> appArgs = new ArrayList<>();

    @Option(names = "--runs-dir", defaultValue = "runs", description = "Where run folders are written (default: ${DEFAULT-VALUE})")
    Path runsDir;

    @Option(names = "--agent-jar", description = "The fault agent jar (default: tool/faultline-agent/target/faultline-agent.jar, next to this CLI)")
    Path agentJar;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        Plan original = Plan.read(planFile);
        long seed = this.seed != null ? this.seed
            : original.traffic() != null && original.traffic().seed() != null ? original.traffic().seed()
            : ThreadLocalRandom.current().nextInt(1, 1_000_000);
        Plan plan = original.forRun(seed, count, rate, appArgs);
        var problems = plan.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("the plan has problems, fix them in " + Out.path(planFile) + ":\n  - " + String.join("\n  - ", problems));
        }

        FaultSpec fault = plan.fault() == null ? null : plan.fault().toSpec();
        var traffic = plan.traffic();
        var requests = Workload.generate(plan.template(), plan.correlateBy().requestField(), seed, traffic.count());
        var effects = new EffectsTable(plan.correlateBy().table(), plan.correlateBy().column());
        var db = plan.database();
        URI target = URI.create(plan.target().url());

        try (var conn = DriverManager.getConnection(db.url(), db.user(), db.password());
             var sender = new TrafficSender(target);
             var lag = new KafkaLag(plan.kafka().bootstrap(), plan.kafka().group(), plan.kafka().topic())) {
            prepare(out, plan, sender, lag);
            // only once the database, Kafka and the app are as expected, so failed attempts leave no empty folders
            Path runDir = createRunDir(runsDir);
            Path runPlan = runDir.resolve("plan.yaml");
            String reproduce = "./faultline run " + Out.path(runPlan);
            plan.write(runPlan, List.of("The plan exactly as this run used it, seed included. Run it again with:", "  " + reproduce));
            Json.writePretty(runDir.resolve("run.json"), new RunInfo(Out.path(planFile), seed, traffic.count(), traffic.rate(), Instant.now()));

            var reset = EffectsTable.reset(conn, plan.resetStatements());
            if (!reset.skipped().isEmpty() && reset.ran() == 0) {
                Out.line(out, "reset     fresh database: the app creates its tables when it starts");
            } else {
                Out.line(out, "reset     %d statements%s", reset.ran(),
                    reset.skipped().isEmpty() ? "" : " (%d skipped: table not created yet)".formatted(reset.skipped().size()));
            }

            AppProcess app = plan.target().jar() == null ? null : startApp(out, plan, sender, fault, runDir);
            try {
                var request = traffic.request();
                Out.line(out, "send      %,d x %s %s at %d/s, seed %d", traffic.count(), request.method(), request.path(), traffic.rate(), seed);
                Path historyFile = runDir.resolve("history.jsonl");
                long sendMillis;
                try (var history = HistoryLog.create(historyFile)) {
                    sendMillis = sender.send(requests, request.method(), request.path(), traffic.rate(), history).toMillis();
                }
                List<HistoryEntry> entries = HistoryLog.read(historyFile);
                Map<Status, Long> byStatus = entries.stream().collect(groupingBy(HistoryEntry::status, counting()));
                var sent = new RunResult.Sent(byStatus.getOrDefault(Status.OK, 0L), byStatus.getOrDefault(Status.FAIL, 0L),
                    byStatus.getOrDefault(Status.INFO, 0L), sendMillis);
                Out.line(out, "          done in %.1fs   ok %d, fail %d, info %d", sendMillis / 1000.0, sent.ok(), sent.fail(), sent.info());

                Out.line(out, "drain     waiting until Kafka lag is 0 and %s stops changing ...", effects.table());
                AppProcess running = app;
                var drained = Drainer.waitUntilDone(() -> {
                    if (running != null) running.checkHealthy();
                    return effects.rows(conn);
                }, lag::lag);
                var drain = new RunResult.Drain(drained.count(), drained.backlog(), drained.settled(), drained.waited().toMillis());
                if (drained.settled()) {
                    Out.line(out, "          %,d rows, lag 0, unchanged for %ds", drained.count(), Drainer.QUIET.toSeconds());
                } else {
                    Out.line(out, "          gave up after %ds with lag %d and %,d rows: 'lost' results may be false",
                        Drainer.MAX_WAIT.toSeconds(), drained.backlog(), drained.count());
                }

                Integer restarts = null;
                if (app != null) {
                    restarts = app.restarts();
                    app.close();
                    app = null;
                }

                var checks = new ArrayList<CheckResult>();
                for (var c : Checker.check(entries, effects.appliedCounts(conn))) {
                    if (plan.checks().contains(c.name())) checks.add(c);
                }
                Integer fired = null;
                if (fault != null) {
                    fired = FaultSpec.fired(runDir);
                    Out.line(out, "faults    %d of %d crashes fired (faults.jsonl), app restarted %d times", fired, fault.times(), restarts);
                    if (plan.checks().contains(Plan.FAULTS_FIRED)) checks.add(Checker.faultsFired(fault.times(), fired));
                }
                boolean pass = checks.stream().allMatch(CheckResult::passed);
                printChecks(out, checks);

                var findings = Findings.of(plan, checks, fired);
                for (Finding f : findings) {
                    Out.line(out, "finding   %-11s %s %s at %s", f.label().name().replace('_', ' '), f.riskId(), f.pattern(), f.location());
                    Out.line(out, "          %s", f.reason());
                }

                Json.writePretty(runDir.resolve("result.json"),
                    new RunResult(pass ? "PASS" : "FAIL", sent, drain, restarts, fired, checks, findings, reproduce));
                Path report = ReportWriter.write(runDir);
                Out.line(out, "%-9s %s", pass ? "PASS" : "FAIL", Out.path(runDir));
                Out.line(out, "report    %s", Out.path(report));
                Out.line(out, "rerun     %s", reproduce);
                return pass ? Faultline.PASS : Faultline.FAIL;
            } finally {
                if (app != null) app.close();
            }
        }
    }

    /** Checks Kafka answers, then makes sure no messages from an earlier run will be processed during this one. */
    private static void prepare(PrintWriter out, Plan plan, TrafficSender sender, KafkaLag lag) throws Exception {
        long backlog;
        try {
            backlog = lag.lag();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the consumer lag from Kafka at " + plan.kafka().bootstrap(), e);
        }
        if (plan.target().jar() != null) {
            if (sender.isReachable()) {
                throw new IllegalStateException("something is already answering at " + plan.target().url()
                    + ": stop it, or remove target.jar from the plan to test the app that is running");
            }
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

    private AppProcess startApp(PrintWriter out, Plan plan, TrafficSender sender, FaultSpec fault, Path runDir) throws Exception {
        Path appJar = Path.of(plan.target().jar());
        if (!Files.isRegularFile(appJar)) {
            throw new IllegalStateException("app jar not found: " + Out.path(appJar) + " (build the app first, for example ./mvnw -q package -DskipTests)");
        }
        Path agent = null;
        Map<String, String> env = Map.of();
        if (fault != null) {
            agent = agentJar != null ? agentJar : defaultAgentJar();
            if (!Files.isRegularFile(agent)) {
                throw new IllegalStateException("fault agent not found at " + agent + ": build it with (cd tool && ./mvnw -q package -DskipTests)");
            }
            env = fault.writeAgentPlan(runDir);
        }

        var app = new AppProcess(AppProcess.command(appJar, agent, plan.target().args()), env, runDir.resolve("app.log"),
            line -> Out.line(out, "%s", line));
        try {
            app.start();
            Out.line(out, "app       started %s (pid %d)%s, waiting for it to answer ...", appJar.getFileName(), app.pid(),
                fault == null ? "" : " with the fault agent");
            long start = System.nanoTime();
            while (!sender.isReachable()) {
                app.checkHealthy();
                if (System.nanoTime() - start > STARTUP_TIMEOUT.toNanos()) {
                    throw new IllegalStateException("the app did not answer at " + plan.target().url() + " within " + STARTUP_TIMEOUT.toSeconds() + "s (see app.log)");
                }
                Thread.sleep(500);
            }
            Out.line(out, "          ready after %.1fs", (System.nanoTime() - start) / 1e9);
            return app;
        } catch (Exception e) {
            app.close();
            throw e;
        }
    }

    /** Next to this CLI: tool/faultline-cli/target/faultline.jar (or target/classes) -> tool/faultline-agent/target/faultline-agent.jar */
    static Path defaultAgentJar() {
        try {
            Path cli = Path.of(RunCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return cli.getParent().getParent().getParent().resolve("faultline-agent").resolve("target").resolve("faultline-agent.jar");
        } catch (Exception e) {
            return Path.of("faultline-agent.jar");
        }
    }

    private static void printChecks(PrintWriter out, List<CheckResult> checks) {
        boolean first = true;
        for (var c : checks) {
            Out.line(out, "%-9s %-14s %s", first ? "check" : "", c.name(), c.passed() ? "PASS" : "FAIL (" + c.offenders().size() + ")");
            for (var offender : c.offenders().stream().limit(SHOW_OFFENDERS).toList()) {
                Out.line(out, "%-24s %s", "", offender);
            }
            if (c.offenders().size() > SHOW_OFFENDERS) {
                Out.line(out, "%-24s ... and %d more (see result.json)", "", c.offenders().size() - SHOW_OFFENDERS);
            }
            first = false;
        }
    }

    /** runs/2026-10-30T04-45-02Z, with a -1, -2 ... suffix if two runs start in the same second. */
    static Path createRunDir(Path runsDir) throws IOException {
        Files.createDirectories(runsDir);
        String stamp = RUN_DIR_NAME.format(Instant.now());
        for (int n = 0; ; n++) {
            try {
                return Files.createDirectory(runsDir.resolve(n == 0 ? stamp : stamp + "-" + n));
            } catch (FileAlreadyExistsException e) {
                // try the next suffix
            }
        }
    }
}
