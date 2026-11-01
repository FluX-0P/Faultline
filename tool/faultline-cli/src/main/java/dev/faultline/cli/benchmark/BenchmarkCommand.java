package dev.faultline.cli.benchmark;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.benchmark.BenchmarkFile.Bug;
import dev.faultline.cli.llm.Asker;
import dev.faultline.cli.plan.Hints;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.plan.PlanCommand;
import dev.faultline.cli.report.Finding;
import dev.faultline.cli.run.PlanRun;
import dev.faultline.cli.scan.Risks;
import dev.faultline.cli.scan.ScanCommand;
import dev.faultline.cli.source.App;
import dev.faultline.cli.understand.AppMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "benchmark", mixinStandardHelpOptions = true,
    description = {"Score Faultline on the demo app's planted bugs. The scan: each bug with only it switched on must be flagged",
        "at its location, and the bug-free build should flag nothing. The runs: each bug's plan with only that bug on, then",
        "every plan again with all bugs off, where nothing may be confirmed. The plans are the hand-written ones, or with",
        "--ai-plans the ones Faultline draws up from each bug's scan. Writes benchmark.json; the exit code is 1 if",
        "anything was confirmed on the bug-free build."})
public final class BenchmarkCommand implements Callable<Integer> {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    @Spec
    CommandSpec spec;

    @Option(names = "--file", defaultValue = "benchmark/bugs.yaml", description = "The benchmark (default: ${DEFAULT-VALUE})")
    Path file;

    @Option(names = "--bug", split = ",", paramLabel = "ID", description = "Only these bugs, such as --bug B1,B10 (default: all)")
    List<String> only = new ArrayList<>();

    @Option(names = "--plan", paramLabel = "FILE", description = "Run this plan instead of each bug's own, such as another fault for --bug B10")
    Path plan;

    @Option(names = "--ai-plans", description = "Run the plans Faultline draws up from each bug's scan, instead of the hand-written ones")
    boolean aiPlans;

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

    @Option(names = "--scan-only", description = "Score the scan alone (with --ai-plans, also draw up the plans): no runs, so no Docker")
    boolean scanOnly;

    @Option(names = "--skip-scan", description = "Leave the scan unscored, so no LLM calls")
    boolean skipScan;

    /**
     * One planted bug's result: whether the scan flagged it, the plan that ran for it (with --ai-plans, whether Faultline
     * drew one up, and what it does or why there is none), and its label with only it switched on, and with every bug off.
     */
    record BugResult(String id, String title, String pattern, String check, Boolean scanFlagged, String plan, Boolean planned, String planNote,
                     String withBug, String withBugReason, String withBugRun, String bugFree, String bugFreeReason, String bugFreeRun) {}

    /**
     * {@code scanFalseFlags}: risks the scan flagged on the bug-free build, where every flag is a false one.
     * {@code planned}: with --ai-plans, the bugs Faultline drew up a plan for.
     */
    record Scores(int bugs, Integer confirmed, Integer falseConfirmations, int bugsWithPattern, Integer scanFlagged, Integer scanFalseFlags,
                  Integer planned) {}

    record Result(Instant at, long seed, String plans, List<BugResult> bugs, Scores scores) {}

    /** Which bugs the scan flagged (by ID), each bug's risks, and how many risks it flagged on the bug-free build. */
    record ScanScore(Map<String, Boolean> flagged, Map<String, Risks> risks, int falseFlags) {}

    /** The plan a bug runs: its file, or null with the reason there is none; {@code note} says what it does. */
    record BugPlan(Path file, String note) {}

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        if (scanOnly && skipScan) throw new IllegalArgumentException("--scan-only and --skip-scan leave nothing to do");
        if (aiPlans && skipScan) throw new IllegalArgumentException("--ai-plans draws up each plan from the bug's scan, so it cannot --skip-scan");
        if (aiPlans && plan != null) throw new IllegalArgumentException("--ai-plans and --plan both choose the plans: use one");
        var bench = BenchmarkFile.read(file);
        var bugs = bench.bugs().stream().filter(b -> only.isEmpty() || only.contains(b.id())).toList();
        if (bugs.isEmpty()) throw new IllegalArgumentException("no bugs match " + only);
        Path dir = Files.createDirectories(runsDir.resolve("benchmark-" + STAMP.format(Instant.now())));
        Path agent = agentJar != null ? agentJar : PlanRun.defaultAgentJar();

        Asker asker = skipScan ? null : asker(out);
        ScanScore scan = asker == null ? null : scan(bench, bugs, asker, dir, out);
        if (aiPlans && scan == null) throw new IllegalStateException("--ai-plans needs the LLM, to scan and to draft the plans");
        var plans = new LinkedHashMap<String, BugPlan>();
        for (Bug bug : bugs) {
            plans.put(bug.id(), aiPlans ? aiPlan(bench, bug, scan.risks().get(bug.id()), asker, dir, out)
                : new BugPlan(this.plan != null ? this.plan : file.getParent().resolve(bug.plan()), "hand-written"));
        }

        var withBug = new LinkedHashMap<String, Run>();
        var bugFree = new LinkedHashMap<String, Run>();
        if (!scanOnly) {
            for (Bug bug : bugs) {
                if (plans.get(bug.id()).file() == null) continue;
                Out.line(out, "\n#### %s: %s (only this bug on)", bug.id(), bug.title());
                withBug.put(bug.id(), run(plans.get(bug.id()).file(), bench.args(bug), dir, agent, out));
            }
            if (!skipBugFree) {
                for (Bug bug : bugs) {
                    if (plans.get(bug.id()).file() == null) continue;
                    Out.line(out, "\n#### %s's plan on the bug-free build (every bug off)", bug.id());
                    bugFree.put(bug.id(), run(plans.get(bug.id()).file(), bench.args(null), dir, agent, out));
                }
            }
        }

        var results = new ArrayList<BugResult>();
        for (Bug bug : bugs) {
            Run on = withBug.get(bug.id());
            Run off = bugFree.get(bug.id());
            BugPlan p = plans.get(bug.id());
            Boolean flagged = scan == null || bug.pattern() == null ? null : scan.flagged().get(bug.id());
            results.add(new BugResult(bug.id(), bug.title(), bug.pattern(), bug.check(), flagged,
                p.file() == null ? null : Out.path(p.file()), aiPlans ? p.file() != null : null, p.note(),
                on == null ? null : on.label(), on == null ? null : on.reason(), on == null ? null : on.dir(),
                off == null ? null : off.label(), off == null ? null : off.reason(), off == null ? null : off.dir()));
        }
        Integer confirmed = scanOnly ? null : (int) results.stream().filter(r -> "confirmed".equals(r.withBug())).count();
        Integer falseConfirmations = scanOnly || skipBugFree ? null : (int) results.stream().filter(r -> "confirmed".equals(r.bugFree())).count();
        int withPattern = (int) bugs.stream().filter(b -> b.pattern() != null).count();
        Integer flagged = scan == null ? null : (int) results.stream().filter(r -> Boolean.TRUE.equals(r.scanFlagged())).count();
        Integer planned = aiPlans ? (int) results.stream().filter(r -> Boolean.TRUE.equals(r.planned())).count() : null;
        var scores = new Scores(bugs.size(), confirmed, falseConfirmations, withPattern, flagged, scan == null ? null : scan.falseFlags(), planned);
        Path json = dir.resolve("benchmark.json");
        Json.writePretty(json, new Result(Instant.now(), seed, aiPlans ? "ai" : plan != null ? Out.path(plan) : "hand-written", results, scores));

        Out.line(out, "\n%-6s %-8s %-9s %-14s %-16s %s", "bug", "pattern", "scan", "with the bug", "bug-free build", "title");
        for (var r : results) {
            String notRun = Boolean.FALSE.equals(r.planned()) ? "not planned" : "-";
            Out.line(out, "%-6s %-8s %-9s %-14s %-16s %s", r.id(), r.pattern() == null ? "-" : r.pattern(),
                r.scanFlagged() == null ? "-" : r.scanFlagged() ? "flagged" : "missed",
                r.withBug() == null ? notRun : r.withBug(), r.bugFree() == null ? notRun : r.bugFree(), r.title());
        }
        if (aiPlans) {
            for (var r : results) Out.line(out, "plan      %-4s %s", r.id(), r.planned() ? r.planNote() : "none: " + r.planNote());
        }
        String runs = scanOnly ? "runs skipped" : "confirmed %d of %d planted bugs, %s false confirmations on the bug-free build"
            .formatted(confirmed, bugs.size(), falseConfirmations == null ? "unknown (skipped)" : String.valueOf(falseConfirmations));
        Out.line(out, "scores    %s%s; scan %s", aiPlans ? "AI plans for %d of %d planted bugs, ".formatted(planned, bugs.size()) : "", runs, scan == null ? "not scored"
            : "flagged %d of %d bugs that have a pattern, %d false flags on the bug-free build".formatted(flagged, withPattern, scan.falseFlags()));
        Out.line(out, "wrote     %s", Out.path(json));
        return falseConfirmations != null && falseConfirmations > 0 ? Faultline.FAIL : Faultline.PASS;
    }

    private static Asker asker(PrintWriter out) {
        try {
            return Asker.fromSettings(message -> Out.line(out, "          %s", message));
        } catch (RuntimeException e) {
            Out.line(out, "scan      not scored: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Scores the scan the way the runs are scored: each bug that has a pattern is scanned for that pattern with only
     * it switched on, and counts as flagged if a risk lands on its location. Then the bug-free build is scanned for every
     * pattern; whatever it flags there is a false flag. Each scan's risks go in the benchmark folder.
     */
    private ScanScore scan(BenchmarkFile bench, List<Bug> bugs, Asker asker, Path dir, PrintWriter out) throws Exception {
        App app = App.read(Path.of(bench.appDir()));
        String appMap = Files.isRegularFile(bench.appMapFile()) ? Files.readString(bench.appMapFile()) : null;
        var flagged = new LinkedHashMap<String, Boolean>();
        var byBug = new LinkedHashMap<String, Risks>();
        for (Bug bug : bugs) {
            if (bug.pattern() == null) continue;
            Out.line(out, "\n#### scan for %s with only it on: is %s flagged at %s?", bug.id(), bug.pattern(), bug.location());
            var settings = bench.settings(bug);
            Risks risks = ScanCommand.scan(app.withSettings(settings), settings, List.of(bug.pattern()), appMap, () -> asker, out);
            Json.writePretty(dir.resolve("scan-" + bug.id() + ".json"), risks);
            flagged.put(bug.id(), risks.risks().stream().anyMatch(r -> r.location().equals(bug.location())));
            byBug.put(bug.id(), risks);
        }
        Out.line(out, "\n#### scan of the bug-free build (every bug off): every risk flagged here is a false flag");
        var settings = bench.settings(null);
        Risks free = ScanCommand.scan(app.withSettings(settings), settings, List.of(), appMap, () -> asker, out);
        Json.writePretty(dir.resolve("scan-bug-free.json"), free);
        return new ScanScore(flagged, byBug, free.risks().size());
    }

    /**
     * Draws up the plan for a bug the way a user would get one: from the risk its scan flagged at its location, with
     * the bug's settings, and approved as drawn. The plan names no settings of the bug's; each run adds them.
     */
    private BugPlan aiPlan(BenchmarkFile bench, Bug bug, Risks risks, Asker asker, Path dir, PrintWriter out) throws Exception {
        if (bug.pattern() == null) return new BugPlan(null, "no code pattern, so the scan has no risk to plan for");
        var risk = risks.risks().stream().filter(r -> r.location().equals(bug.location()) && bug.pattern().equals(r.pattern())).findFirst();
        if (risk.isEmpty()) return new BugPlan(null, "the scan did not flag " + bug.location());
        if (bench.compose() == null) throw new IllegalArgumentException(file + " needs compose: the file and profile the app's services are in");
        Out.line(out, "\n#### plan for %s from its scan's risk %s at %s", bug.id(), risk.get().id(), bug.location());
        AppMap map = PlanCommand.readJson(bench.appMapFile(), AppMap.class, "understand " + bench.appDir());
        var settings = bench.settings(bug);
        App app = App.read(Path.of(bench.appDir())).withSettings(settings);
        int port = Integer.parseInt(app.config().get("server.port").orElse("8080"));
        Deployment deployment = Deployment.compose(bench.compose().file(), null, bench.compose().profile(), Path.of(bench.appDir()), port);
        try {
            var drawn = PlanCommand.draw(app, map, risk.get(), deployment, Map.of(), Hints.read(Path.of(bench.appDir())), asker, out);
            Path planFile = dir.resolve("plan-" + bug.id() + ".yaml");
            drawn.plan().write(planFile, List.of("Drawn up by faultline benchmark --ai-plans for " + bug.id() + " with " + drawn.model() + ".",
                "Tests %s %s at %s: %s.".formatted(risk.get().id(), risk.get().pattern(), risk.get().location(), drawn.does())));
            return new BugPlan(planFile, drawn.does());
        } catch (IllegalStateException e) {
            Out.line(out, "plan      none: %s", e.getMessage());
            return new BugPlan(null, e.getMessage());
        }
    }

    private record Run(String label, String reason, String dir) {}

    private Run run(Path planFile, List<String> args, Path dir, Path agent, PrintWriter out) throws Exception {
        Plan plan = Plan.read(planFile).forRun(seed, null, null, args, runs, baselines);
        var problems = plan.problems();
        if (!problems.isEmpty()) throw new IllegalArgumentException(planFile + " has problems: " + String.join("; ", problems));
        var outcome = PlanRun.run(plan, Out.path(planFile), dir, agent, out);
        Finding f = outcome.result().findings().stream().findFirst().orElse(null);
        String label = f == null ? "no finding" : f.label().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return new Run(label, f == null ? null : f.reason(), Out.path(outcome.dir()));
    }
}
