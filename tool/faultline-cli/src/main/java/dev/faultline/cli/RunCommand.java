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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.check.Checker;
import dev.faultline.cli.drain.Drainer;
import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.history.HistoryEntry.Status;
import dev.faultline.cli.history.HistoryLog;
import dev.faultline.cli.traffic.TrafficSender;
import dev.faultline.cli.workload.PaymentWorkload;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "run", mixinStandardHelpOptions = true,
    description = "Send seeded payments to the app, wait for it to finish, and check nothing was lost or duplicated.")
final class RunCommand implements Callable<Integer> {
    private static final int SHOW_OFFENDERS = 10;
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

    @Option(names = "--count", defaultValue = "2000", description = "Payments to send (default: ${DEFAULT-VALUE})")
    int count;

    @Option(names = "--rate", defaultValue = "100", description = "Payments per second (default: ${DEFAULT-VALUE})")
    int rate;

    @Option(names = "--seed", description = "Seed for the generated payments. Same seed, same payments (default: random, printed)")
    Long seed;

    @Option(names = "--runs-dir", defaultValue = "runs", description = "Where run folders are written (default: ${DEFAULT-VALUE})")
    Path runsDir;

    record RunInfo(long seed, int count, int rate, URI target, String db, Instant startedAt) {}

    record Sent(long ok, long fail, long info, long millis) {}

    record Drain(long rows, boolean settled, long waitedMillis) {}

    record RunResult(String verdict, Sent sent, Drain drain, List<CheckResult> checks) {}

    @Override
    public Integer call() throws Exception {
        if (count < 1) throw new ParameterException(spec.commandLine(), "--count must be at least 1");
        if (rate < 1) throw new ParameterException(spec.commandLine(), "--rate must be at least 1");
        long seed = this.seed != null ? this.seed : ThreadLocalRandom.current().nextInt(1, 1_000_000);
        PrintWriter out = spec.commandLine().getOut();

        var workload = new PaymentWorkload();
        var payments = workload.generate(seed, count);

        try (var db = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             var sender = new TrafficSender(target)) {
            sender.checkReachable();
            // only once the database and the app both answer, so failed attempts leave no empty folders
            Path runDir = createRunDir(runsDir);
            Json.writePretty(runDir.resolve("run.json"), new RunInfo(seed, count, rate, target, dbUrl, Instant.now()));

            workload.reset(db);
            print(out, "reset     payments emptied, %d balances set to %,d", PaymentWorkload.ACCOUNTS, PaymentWorkload.INITIAL_BALANCE);

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

            print(out, "drain     waiting until the payments table stops changing ...");
            var drained = Drainer.waitUntilStable(() -> workload.appliedRows(db));
            var drain = new Drain(drained.count(), drained.settled(), drained.waited().toMillis());
            if (drained.settled()) {
                print(out, "          %,d rows, unchanged for %ds", drained.count(), Drainer.QUIET.toSeconds());
            } else {
                print(out, "          still changing after %ds (%,d rows): 'lost' results may be false", Drainer.MAX_WAIT.toSeconds(), drained.count());
            }

            var checks = Checker.check(entries, workload.appliedCounts(db), workload.balances(db), PaymentWorkload.INITIAL_BALANCE);
            boolean pass = checks.stream().allMatch(CheckResult::passed);
            printChecks(out, checks);

            Json.writePretty(runDir.resolve("result.json"), new RunResult(pass ? "PASS" : "FAIL", sent, drain, checks));
            print(out, "%-9s %s", pass ? "PASS" : "FAIL", runDir);
            print(out, "rerun     faultline run --seed %d --count %d --rate %d", seed, count, rate);
            return pass ? Faultline.PASS : Faultline.FAIL;
        }
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
        out.println(format.formatted(args));
        out.flush();
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
