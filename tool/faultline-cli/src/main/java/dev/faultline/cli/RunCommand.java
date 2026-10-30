package dev.faultline.cli;

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

import dev.faultline.cli.app.AppProcess;
import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.check.Checker;
import dev.faultline.cli.drain.Drainer;
import dev.faultline.cli.fault.FaultSpec;
import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.history.HistoryEntry.Status;
import dev.faultline.cli.history.HistoryLog;
import dev.faultline.cli.kafka.KafkaLag;
import dev.faultline.cli.traffic.TrafficSender;
import dev.faultline.cli.workload.PaymentWorkload;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "run", mixinStandardHelpOptions = true,
    description = {
        "Send seeded payments to the app, wait for it to finish, and check nothing was lost or duplicated.",
        "With --app-jar, Faultline starts the app itself; with --fault, it also crashes the app at that point and restarts it."})
final class RunCommand implements Callable<Integer> {
    private static final int SHOW_OFFENDERS = 10;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final DateTimeFormatter RUN_DIR_NAME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    @Spec
    CommandSpec spec;

    @Option(names = "--target", defaultValue = "http://localhost:8080", description = "Base URL of the app (default: ${DEFAULT-VALUE})")
    URI target;

    @Option(names = "--db", defaultValue = "jdbc:postgresql://localhost:5432/demo", description = "JDBC URL of the app's database (default: ${DEFAULT-VALUE})")
    String dbUrl;

    @Option(names = "--db-user", defaultValue = "demo", description = "Database user (default: ${DEFAULT-VALUE})")
    String dbUser;

    @Option(names = "--db-password", defaultValue = "demo", description = "Database password (default: ${DEFAULT-VALUE})")
    String dbPassword;

    @Option(names = "--kafka", defaultValue = "localhost:9092", description = "Kafka bootstrap servers, to read the app's consumer lag (default: ${DEFAULT-VALUE})")
    String kafka;

    @Option(names = "--count", defaultValue = "2000", description = "Payments to send (default: ${DEFAULT-VALUE})")
    int count;

    @Option(names = "--rate", defaultValue = "100", description = "Payments per second (default: ${DEFAULT-VALUE})")
    int rate;

    @Option(names = "--seed", description = "Seed for the generated payments. Same seed, same payments (default: random, printed)")
    Long seed;

    @Option(names = "--runs-dir", defaultValue = "runs", description = "Where run folders are written (default: ${DEFAULT-VALUE})")
    Path runsDir;

    @Option(names = "--app-jar", description = "Start this Spring Boot jar, and restart it after each planned crash. Without it, the app must already be running at --target")
    Path appJar;

    @Option(names = "--app-arg", description = "An argument for the app, repeatable. Use the = form: --app-arg=--demo.fixes.dedupe=true")
    List<String> appArgs = new ArrayList<>();

    @Option(names = "--fault", description = "Crash the app at this point (needs --app-jar). Known points: before-ack")
    String faultPoint;

    @Option(names = "--nth-hit", defaultValue = "300", description = "Crash on this hit of the point, counted from each app start (default: ${DEFAULT-VALUE})")
    long nthHit;

    @Option(names = "--times", defaultValue = "3", description = "Crashes in total, across restarts (default: ${DEFAULT-VALUE})")
    int times;

    @Option(names = "--agent-jar", description = "The fault agent jar (default: tool/faultline-agent/target/faultline-agent.jar, next to this CLI)")
    Path agentJar;

    record RunInfo(long seed, int count, int rate, String target, String db, String kafka,
                   String appJar, List<String> appArgs, FaultSpec fault, Instant startedAt) {}

    record Sent(long ok, long fail, long info, long millis) {}

    record Drain(long rows, long backlog, boolean settled, long waitedMillis) {}

    record RunResult(String verdict, Sent sent, Drain drain, Integer restarts, Integer faultsFired, List<CheckResult> checks) {}

    @Override
    public Integer call() throws Exception {
        FaultSpec fault = validate();
        long seed = this.seed != null ? this.seed : ThreadLocalRandom.current().nextInt(1, 1_000_000);
        PrintWriter out = spec.commandLine().getOut();

        var workload = new PaymentWorkload();
        var payments = workload.generate(seed, count);

        try (var db = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             var sender = new TrafficSender(target);
             var lag = new KafkaLag(kafka, PaymentWorkload.GROUP, PaymentWorkload.TOPIC)) {
            prepare(out, sender, lag);
            // only once the database, Kafka and the app are as expected, so failed attempts leave no empty folders
            Path runDir = createRunDir(runsDir);
            Json.writePretty(runDir.resolve("run.json"), new RunInfo(seed, count, rate, target.toString(), dbUrl, kafka,
                appJar == null ? null : appJar.toString(), appArgs, fault, Instant.now()));

            if (workload.reset(db)) {
                print(out, "reset     payments emptied, %d balances set to %,d", PaymentWorkload.ACCOUNTS, PaymentWorkload.INITIAL_BALANCE);
            } else {
                print(out, "reset     fresh database: the app creates its tables and balances when it starts");
            }

            AppProcess app = appJar == null ? null : startApp(out, sender, fault, runDir);
            try {
                print(out, "send      %,d payments at %d/s, seed %d", count, rate, seed);
                Path historyFile = runDir.resolve("history.jsonl");
                long sendMillis;
                try (var history = HistoryLog.create(historyFile)) {
                    sendMillis = sender.send(payments, PaymentWorkload.PATH, workload::requestBody, rate, history).toMillis();
                }
                List<HistoryEntry> entries = HistoryLog.read(historyFile);
                Map<Status, Long> byStatus = entries.stream().collect(groupingBy(HistoryEntry::status, counting()));
                var sent = new Sent(byStatus.getOrDefault(Status.OK, 0L), byStatus.getOrDefault(Status.FAIL, 0L),
                    byStatus.getOrDefault(Status.INFO, 0L), sendMillis);
                print(out, "          done in %.1fs   ok %d, fail %d, info %d", sendMillis / 1000.0, sent.ok(), sent.fail(), sent.info());

                print(out, "drain     waiting until Kafka lag is 0 and the payments table stops changing ...");
                AppProcess running = app;
                var drained = Drainer.waitUntilDone(() -> {
                    if (running != null) running.checkHealthy();
                    return workload.appliedRows(db);
                }, lag::lag);
                var drain = new Drain(drained.count(), drained.backlog(), drained.settled(), drained.waited().toMillis());
                if (drained.settled()) {
                    print(out, "          %,d rows, lag 0, unchanged for %ds", drained.count(), Drainer.QUIET.toSeconds());
                } else {
                    print(out, "          gave up after %ds with lag %d and %,d rows: 'lost' results may be false",
                        Drainer.MAX_WAIT.toSeconds(), drained.backlog(), drained.count());
                }

                Integer restarts = null;
                if (app != null) {
                    restarts = app.restarts();
                    app.close();
                    app = null;
                }

                var checks = new ArrayList<>(Checker.check(entries, workload.appliedCounts(db), workload.balances(db), PaymentWorkload.INITIAL_BALANCE));
                Integer fired = null;
                if (fault != null) {
                    fired = FaultSpec.fired(runDir);
                    print(out, "faults    %d of %d crashes fired (faults.jsonl), app restarted %d times", fired, fault.times(), restarts);
                    checks.add(Checker.faultsFired(fault.times(), fired));
                }
                boolean pass = checks.stream().allMatch(CheckResult::passed);
                printChecks(out, checks);

                Json.writePretty(runDir.resolve("result.json"), new RunResult(pass ? "PASS" : "FAIL", sent, drain, restarts, fired, checks));
                print(out, "%-9s %s", pass ? "PASS" : "FAIL", runDir);
                print(out, "rerun     %s", rerunCommand(seed, fault));
                return pass ? Faultline.PASS : Faultline.FAIL;
            } finally {
                if (app != null) app.close();
            }
        }
    }

    private FaultSpec validate() {
        if (count < 1) throw new ParameterException(spec.commandLine(), "--count must be at least 1");
        if (rate < 1) throw new ParameterException(spec.commandLine(), "--rate must be at least 1");
        if (appJar == null && !appArgs.isEmpty()) throw new ParameterException(spec.commandLine(), "--app-arg needs --app-jar");
        if (faultPoint == null) return null;
        if (appJar == null) {
            throw new ParameterException(spec.commandLine(), "--fault needs --app-jar: Faultline has to start the app to attach its agent");
        }
        try {
            return new FaultSpec(faultPoint, nthHit, times);
        } catch (IllegalArgumentException e) {
            throw new ParameterException(spec.commandLine(), e.getMessage());
        }
    }

    /** Checks Kafka answers, then makes sure no messages from an earlier run will be processed during this one. */
    private void prepare(PrintWriter out, TrafficSender sender, KafkaLag lag) throws Exception {
        long backlog;
        try {
            backlog = lag.lag();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the consumer lag from Kafka at " + kafka, e);
        }
        if (appJar != null) {
            if (sender.isReachable()) {
                throw new IllegalStateException("something is already answering at " + target
                    + ": stop it, or leave out --app-jar to test the app that is running");
            }
            long skipped = lag.skipBacklog();
            if (skipped > 0) print(out, "kafka     skipped %,d leftover messages from an earlier run", skipped);
        } else {
            sender.checkReachable();
            if (backlog > 0) {
                print(out, "kafka     waiting for the app to finish %,d leftover messages from an earlier run ...", backlog);
                var settled = Drainer.waitUntilDone(() -> 0, lag::lag);
                if (!settled.settled()) throw new IllegalStateException("the app still has " + settled.backlog() + " leftover messages");
            }
        }
    }

    private AppProcess startApp(PrintWriter out, TrafficSender sender, FaultSpec fault, Path runDir) throws Exception {
        if (!Files.isRegularFile(appJar)) throw new IllegalStateException("app jar not found: " + appJar);
        Path agent = null;
        Map<String, String> env = Map.of();
        if (fault != null) {
            agent = agentJar != null ? agentJar : defaultAgentJar();
            if (!Files.isRegularFile(agent)) {
                throw new IllegalStateException("fault agent not found at " + agent + ": build it with (cd tool && ./mvnw -q package -DskipTests)");
            }
            env = fault.writeAgentPlan(runDir);
        }

        var app = new AppProcess(AppProcess.command(appJar, agent, appArgs), env, runDir.resolve("app.log"), line -> print(out, "%s", line));
        try {
            app.start();
            print(out, "app       started %s (pid %d)%s, waiting for it to answer ...", appJar.getFileName(), app.pid(),
                fault == null ? "" : " with the fault agent");
            long start = System.nanoTime();
            while (!sender.isReachable()) {
                app.checkHealthy();
                if (System.nanoTime() - start > STARTUP_TIMEOUT.toNanos()) {
                    throw new IllegalStateException("the app did not answer at " + target + " within " + STARTUP_TIMEOUT.toSeconds() + "s (see app.log)");
                }
                Thread.sleep(500);
            }
            print(out, "          ready after %.1fs", (System.nanoTime() - start) / 1e9);
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

    private String rerunCommand(long seed, FaultSpec fault) {
        var parts = new ArrayList<>(List.of("faultline run", "--seed " + seed, "--count " + count, "--rate " + rate));
        var parsed = spec.commandLine().getParseResult();
        for (String option : List.of("--target", "--db", "--kafka")) {
            if (parsed.hasMatchedOption(option)) parts.add(option + " " + parsed.matchedOptionValue(option, null));
        }
        if (appJar != null) parts.add("--app-jar " + appJar);
        for (String arg : appArgs) parts.add("--app-arg=" + arg);
        if (fault != null) parts.add("--fault %s --nth-hit %d --times %d".formatted(fault.point(), fault.nthHit(), fault.times()));
        return String.join(" ", parts);
    }

    private static void printChecks(PrintWriter out, List<CheckResult> checks) {
        boolean first = true;
        for (var c : checks) {
            print(out, "%-9s %-14s %s", first ? "check" : "", c.name(), c.passed() ? "PASS" : "FAIL (" + c.offenders().size() + ")");
            for (var offender : c.offenders().stream().limit(SHOW_OFFENDERS).toList()) {
                print(out, "%-24s %s", "", offender);
            }
            if (c.offenders().size() > SHOW_OFFENDERS) {
                print(out, "%-24s ... and %d more (see result.json)", "", c.offenders().size() - SHOW_OFFENDERS);
            }
            first = false;
        }
    }

    private static void print(PrintWriter out, String format, Object... args) {
        synchronized (out) {
            out.println(format.formatted(args));
            out.flush();
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
