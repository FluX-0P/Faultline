package dev.faultline.cli.run;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.report.Finding;
import dev.faultline.cli.report.Findings;
import dev.faultline.cli.report.ReportWriter;

/**
 * Runs a plan as its confirm settings say: {@code runs} times with the fault, then {@code baselines} times without it,
 * each in its own sub-folder. Then labels the plan's risk, and writes result.json and report.html.
 */
public final class PlanRun {
    private static final DateTimeFormatter RUN_DIR_NAME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    public record Outcome(Path dir, RunResult result) {}

    private PlanRun() {}

    /** @param plan a plan with its seed set, as {@link Plan#forRun} returns it */
    public static Outcome run(Plan plan, String source, Path runsDir, Path agentJar, PrintWriter out) throws Exception {
        Path dir = createRunDir(runsDir);
        Path planCopy = dir.resolve("plan.yaml");
        String reproduce = "./faultline run " + Out.path(planCopy);
        plan.write(planCopy, List.of("The plan exactly as this run used it, seed included. Run it again with:", "  " + reproduce));
        var confirm = plan.confirm();
        var traffic = plan.traffic();
        Json.writePretty(dir.resolve("run.json"),
            new RunInfo(source, traffic.seed(), traffic.count(), traffic.rate(), confirm.runs(), confirm.baselines(), Instant.now()));

        var attempts = new ArrayList<AttemptResult>();
        for (int i = 1; i <= confirm.runs(); i++) {
            String name = "run-" + i;
            Out.line(out, "== %s of %d%s ==", name, confirm.runs(), plan.fault() == null ? "" : ", with the fault");
            attempts.add(Attempt.run(name, AttemptResult.FAULT, plan, dir.resolve(name), agentJar, out));
        }
        for (int i = 1; i <= confirm.baselines(); i++) {
            String name = "baseline-" + i;
            Out.line(out, "== %s of %d, without the fault ==", name, confirm.baselines());
            attempts.add(Attempt.run(name, AttemptResult.BASELINE, plan.withoutFault(), dir.resolve(name), agentJar, out));
        }

        List<Finding> findings = Findings.of(plan, attempts);
        boolean pass = attempts.stream().allMatch(AttemptResult::allPassed);
        var result = new RunResult(pass ? "PASS" : "FAIL", attempts, findings, reproduce);
        Json.writePretty(dir.resolve("result.json"), result);
        Path report = ReportWriter.write(dir);

        Out.line(out, "==");
        for (Finding f : findings) {
            Out.line(out, "finding   %-11s %s %sat %s", f.label().name().replace('_', ' '), f.riskId(), f.pattern() == null ? "" : f.pattern() + " ", f.location());
            Out.line(out, "          %s", f.reason());
        }
        Out.line(out, "%-9s %s", result.verdict(), Out.path(dir));
        Out.line(out, "report    %s", Out.path(report));
        Out.line(out, "rerun     %s", reproduce);
        return new Outcome(dir, result);
    }

    /** Next to this CLI: tool/faultline-cli/target/faultline.jar (or target/classes) -> tool/faultline-agent/target/faultline-agent.jar */
    public static Path defaultAgentJar() {
        try {
            Path cli = Path.of(PlanRun.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return cli.getParent().getParent().getParent().resolve("faultline-agent").resolve("target").resolve("faultline-agent.jar");
        } catch (Exception e) {
            return Path.of("faultline-agent.jar");
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
