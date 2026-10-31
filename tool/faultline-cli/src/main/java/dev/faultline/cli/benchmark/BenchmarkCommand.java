package dev.faultline.cli.benchmark;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.benchmark.BenchmarkFile.Bug;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.report.Finding;
import dev.faultline.cli.run.PlanRun;
import dev.faultline.cli.scan.Risks;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "benchmark", mixinStandardHelpOptions = true,
    description = {"Score Faultline on the demo app's planted bugs: run each bug's plan with only that bug switched on, then",
        "every plan again with all bugs off, where nothing may be confirmed. Writes benchmark.json; the exit code is 1",
        "if anything was confirmed on the bug-free build."})
public final class BenchmarkCommand implements Callable<Integer> {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    @Spec
    CommandSpec spec;

    @Option(names = "--file", defaultValue = "benchmark/bugs.yaml", description = "The benchmark (default: ${DEFAULT-VALUE})")
    Path file;

    @Option(names = "--bug", split = ",", paramLabel = "ID", description = "Only these bugs, such as --bug B1,B10 (default: all)")
    List<String> only = new ArrayList<>();

    @Option(names = "--seed", defaultValue = "42", description = "Seed for every run (default: ${DEFAULT-VALUE})")
    long seed;

    @Option(names = "--runs", description = "Runs with the fault per plan (default: each plan's confirm.runs)")
    Integer runs;

    @Option(names = "--baselines", description = "Runs without the fault per plan (default: each plan's confirm.baselines)")
    Integer baselines;

    @Option(names = "--skip-bug-free", description = "Skip the runs with every bug off")
    boolean skipBugFree;

    @Option(names = "--runs-dir", defaultValue = "runs", description = "Where the benchmark folder is written (default: ${DEFAULT-VALUE})")
    Path runsDir;

    @Option(names = "--agent-jar", description = "The fault agent jar (default: next to this CLI)")
    Path agentJar;

    /** One planted bug's result: its label with only it switched on, and with every bug off. */
    record BugResult(String id, String title, String pattern, String check, Boolean scanFlagged,
                     String withBug, String withBugReason, String withBugRun, String bugFree, String bugFreeReason, String bugFreeRun) {}

    record Scores(int bugs, int confirmed, int falseConfirmations, int bugsWithPattern, Integer scanFlagged) {}

    record Result(Instant at, long seed, List<BugResult> bugs, Scores scores) {}

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        var bench = BenchmarkFile.read(file);
        var bugs = bench.bugs().stream().filter(b -> only.isEmpty() || only.contains(b.id())).toList();
        if (bugs.isEmpty()) throw new IllegalArgumentException("no bugs match " + only);
        Path dir = Files.createDirectories(runsDir.resolve("benchmark-" + STAMP.format(Instant.now())));
        Path agent = agentJar != null ? agentJar : PlanRun.defaultAgentJar();
        Risks risks = Files.isRegularFile(bench.risksFile()) ? Json.MAPPER.readValue(bench.risksFile().toFile(), Risks.class) : null;

        var withBug = new ArrayList<Run>();
        for (Bug bug : bugs) {
            Out.line(out, "\n#### %s: %s (only this bug on)", bug.id(), bug.title());
            withBug.add(run(bug, bench.args(bug), dir, agent, out));
        }
        var bugFree = new ArrayList<Run>();
        if (!skipBugFree) {
            for (Bug bug : bugs) {
                Out.line(out, "\n#### %s's plan on the bug-free build (every bug off)", bug.id());
                bugFree.add(run(bug, bench.args(null), dir, agent, out));
            }
        }

        var results = new ArrayList<BugResult>();
        for (int i = 0; i < bugs.size(); i++) {
            Bug bug = bugs.get(i);
            Run on = withBug.get(i);
            Run off = skipBugFree ? null : bugFree.get(i);
            Boolean flagged = bug.pattern() == null || risks == null ? null
                : risks.risks().stream().anyMatch(r -> r.pattern().equals(bug.pattern()) && r.location().equals(bug.location()));
            results.add(new BugResult(bug.id(), bug.title(), bug.pattern(), bug.check(), flagged, on.label(), on.reason(), on.dir(),
                off == null ? null : off.label(), off == null ? null : off.reason(), off == null ? null : off.dir()));
        }
        int confirmed = (int) results.stream().filter(r -> "confirmed".equals(r.withBug())).count();
        int falseConfirmations = (int) results.stream().filter(r -> "confirmed".equals(r.bugFree())).count();
        int withPattern = (int) bugs.stream().filter(b -> b.pattern() != null).count();
        Integer flagged = risks == null ? null : (int) results.stream().filter(r -> Boolean.TRUE.equals(r.scanFlagged())).count();
        var scores = new Scores(bugs.size(), confirmed, falseConfirmations, withPattern, flagged);
        Path json = dir.resolve("benchmark.json");
        Json.writePretty(json, new Result(Instant.now(), seed, results, scores));

        Out.line(out, "\n%-6s %-8s %-14s %-16s %s", "bug", "pattern", "with the bug", "bug-free build", "title");
        for (var r : results) {
            Out.line(out, "%-6s %-8s %-14s %-16s %s", r.id(), r.pattern() == null ? "-" : r.pattern(), r.withBug(),
                r.bugFree() == null ? "skipped" : r.bugFree(), r.title());
        }
        Out.line(out, "scores    confirmed %d of %d planted bugs, %s false confirmations on the bug-free build, scan %s",
            confirmed, bugs.size(), skipBugFree ? "unknown (skipped)" : String.valueOf(falseConfirmations),
            flagged == null ? "not measured (run ./faultline scan " + bench.appDir() + " first)" : "flagged %d of %d bugs that have a pattern".formatted(flagged, withPattern));
        Out.line(out, "wrote     %s", Out.path(json));
        return falseConfirmations > 0 ? Faultline.FAIL : Faultline.PASS;
    }

    private record Run(String label, String reason, String dir) {}

    private Run run(Bug bug, List<String> args, Path dir, Path agent, PrintWriter out) throws Exception {
        Path planFile = file.getParent().resolve(bug.plan());
        Plan plan = Plan.read(planFile).forRun(seed, null, null, args, runs, baselines);
        var problems = plan.problems();
        if (!problems.isEmpty()) throw new IllegalArgumentException(planFile + " has problems: " + String.join("; ", problems));
        var outcome = PlanRun.run(plan, Out.path(planFile), dir, agent, out);
        Finding f = outcome.result().findings().stream().findFirst().orElse(null);
        String label = f == null ? "no finding" : f.label().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return new Run(label, f == null ? null : f.reason(), Out.path(outcome.dir()));
    }
}
