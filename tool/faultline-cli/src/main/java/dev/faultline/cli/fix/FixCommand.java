package dev.faultline.cli.fix;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.javaparser.ast.body.TypeDeclaration;
import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.app.Environment;
import dev.faultline.cli.llm.Asker;
import dev.faultline.cli.llm.Prompt;
import dev.faultline.cli.llm.Schema;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.report.Finding;
import dev.faultline.cli.report.Fix;
import dev.faultline.cli.report.ReportWriter;
import dev.faultline.cli.run.RunResult;
import dev.faultline.cli.scan.RiskPattern;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.JavaSources.Method;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Asks the LLM how to fix each confirmed finding of a run, from the risky code, the classes around it and what the run
 * saw, and adds the suggestions to the run's report, marked as machine-made. It changes no code: a person reads the
 * suggestion, applies it if it is right, and runs the plan again to prove it.
 */
@Command(name = "fix", mixinStandardHelpOptions = true,
    description = "Suggest a fix for each confirmed finding of a run (machine-made: check it, apply it yourself, run the plan again)")
public final class FixCommand implements Callable<Integer> {
    static final String SYSTEM = """
        You suggest how to fix a bug that Faultline, a tool that crash-tests Spring Boot + Kafka apps, has proven by
        breaking the app on purpose: the run with the fault lost, doubled or corrupted data, and the same run without
        the fault did not. You get the risky pattern, the code around the bug, the fault and what the checks saw.

        Rules:
        - Fix the cause the pattern names, in the app's own code and style, with what the app already uses (its
          database, its Kafka setup, its libraries). Add no new framework.
        - The smallest change that makes the fault harmless. Show the code as it should be, not a whole file.
        - The errors the app logged show where it fails. When one names what to change, such as a framework's own advice
          in its message, follow it: the fix must handle that error, not only the general case.
        - Say what else the fix needs, such as a migration for a new column or constraint.
        - Your suggestion is labelled as machine-made: a person checks it and runs the plan again to prove it.""";

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "RUN_FOLDER", description = "A finished run folder, such as runs/2026-10-31T10-15-00Z")
    Path runDir;

    @Option(names = "--app", required = true, paramLabel = "APP_FOLDER", description = "The app's folder, whose code the run tested")
    Path appDir;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        if (!Files.isRegularFile(runDir.resolve("result.json"))) {
            throw new IllegalArgumentException(runDir + " has no result.json: is it a finished run folder?");
        }
        var result = Json.MAPPER.readValue(runDir.resolve("result.json").toFile(), RunResult.class);
        Plan plan = Plan.read(runDir.resolve("plan.yaml"));
        var confirmed = result.findings().stream().filter(f -> f.label() == Finding.Label.CONFIRMED).toList();
        if (confirmed.isEmpty()) {
            Out.line(out, "fix       no confirmed finding in %s: nothing to fix", Out.path(runDir));
            return Faultline.PASS;
        }
        App app = App.read(appDir, Environment.read(Workspace.forApp(appDir)).orElse(null));
        var asker = Asker.fromSettings(message -> Out.line(out, "          %s", message));
        var fixes = new ArrayList<Fix>();
        for (var f : confirmed) {
            var method = app.code().method(f.location()).orElseThrow(() -> new IllegalStateException(
                f.location() + " is not in the code of " + Out.path(appDir) + ": is it the app this run tested?"));
            Out.line(out, "fix       %s %s at %s: asking %s ...", f.riskId(), f.pattern() == null ? "" : f.pattern(), f.location(), asker.model());
            var answer = asker.ask(SYSTEM, prompt(app, plan, f, method, loggedErrors(runDir)), Schema.load("fix-suggestion"), Suggestion.class,
                Suggestion::problems);
            var s = answer.value();
            fixes.add(new Fix(f.riskId(), f.location(), answer.model(), s.summary(), s.file(), s.change(), s.why(), s.risks()));
            Out.line(out, "          %s%s", answer.cached() ? "(cached) " : "", s.summary());
        }
        Path file = runDir.resolve(Fix.FILE);
        Json.writePretty(file, fixes);
        Out.line(out, "wrote     %s", Out.path(file));
        Out.line(out, "report    %s", Out.path(ReportWriter.write(runDir)));
        Out.line(out, "next      check each suggestion, apply it yourself, then ./faultline run %s", Out.path(runDir.resolve("plan.yaml")));
        return Faultline.PASS;
    }

    // an exception or error line, after the service's and the logger's prefixes: "SerializationException: Error deserializing ..."
    private static final Pattern ERROR = Pattern.compile("((?:[\\w$]+\\.)*[\\w$]*(?:Exception|Error)(?::\\s.*)?)$");
    private static final int ERRORS = 6;

    /**
     * The errors the app logged most often in the runs with the fault, each once with how often: what the checks
     * cannot see, such as why a listener retries a message forever. Never part of a verdict, only of the suggestion.
     */
    static String loggedErrors(Path runDir) throws IOException {
        var counts = new LinkedHashMap<String, Integer>();
        try (var attempts = Files.list(runDir)) {
            for (Path log : attempts.filter(d -> d.getFileName().toString().startsWith("run-")).map(d -> d.resolve("app.log")).filter(Files::isRegularFile).toList()) {
                try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
                    lines.map(l -> l.contains(" | ") ? l.substring(l.indexOf(" | ") + 3) : l)
                        .map(l -> l.replaceFirst("^\\S*\\d{2}:\\d{2}:\\d{2}\\S*\\s+", "").strip())
                        .map(ERROR::matcher).filter(Matcher::find).map(m -> m.group(1))
                        .map(e -> e.length() > 300 ? e.substring(0, 300) : e)
                        .forEach(e -> counts.merge(e, 1, Integer::sum));
                }
            }
        }
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(ERRORS)
            .map(e -> "- %dx %s".formatted(e.getValue(), e.getKey())).collect(Collectors.joining("\n"));
    }

    /** The pattern, the fault and what the checks saw, then the risky code and the classes it works with. */
    static String prompt(App app, Plan plan, Finding f, Method method) {
        return prompt(app, plan, f, method, null);
    }

    /** @param errors the errors the app logged most during the runs with the fault, or null */
    static String prompt(App app, Plan plan, Finding f, Method method, String errors) {
        var pattern = f.pattern() == null ? null : RiskPattern.byId(f.pattern()).orElse(null);
        var types = new LinkedHashMap<String, TypeDeclaration<?>>();
        types.put(method.owner().getNameAsString(), method.owner());
        app.code().withOwnCallees(method).forEach(m -> app.code().calledTypes(m).forEach(t -> types.putIfAbsent(t.getNameAsString(), t)));
        app.code().callers(method).forEach(c -> types.putIfAbsent(c.owner().getNameAsString(), c.owner()));
        var modules = new LinkedHashSet<App.Module>();
        app.moduleOf(method).ifPresent(modules::add);
        var fault = plan.fault();
        return new Prompt()
            .line("The bug: %s%s at %s (%s, line %d).".formatted(f.pattern() == null ? "" : f.pattern() + " ", f.title(), f.location(), f.file(), f.line()))
            .section("The pattern", pattern == null ? null : pattern.risk())
            .line("The fault Faultline fired: %s%s%s%s.".formatted(fault.layer() == null ? "code" : fault.layer(), " " + fault.action(),
                fault.point() == null ? "" : fault.point().equals("before-ack") ? " just before the listener acknowledges" : " " + (fault.when() == null ? "before" : fault.when()) + " " + fault.point(),
                fault.target() == null ? "" : " on " + fault.target()))
            .line("What the run saw: " + f.reason())
            .section("Evidence (requests and rows the checks flagged)", f.evidence().isEmpty() ? null
                : f.evidence().stream().limit(10).map(e -> "- " + e).collect(Collectors.joining("\n")))
            .line("Why the scan flagged it: " + f.why())
            .section("The errors the app logged most during the runs with the fault (how often, then the error)", errors)
            .code("Code (the risky method's class, the classes it calls and the classes that call it)", "java",
                types.values().stream().map(app.code()::source).collect(Collectors.joining("\n\n")))
            .codes(app.configSections("Spring configuration (flattened)", modules), "properties")
            .files("Database schema (migrations)", app.files().migrations(), "sql")
            .code(Environment.SCHEMA_TITLE, "sql", app.liveSchema())
            .toString();
    }

    /** The LLM's answer. */
    record Suggestion(String why, String summary, String file, String change, String risks) {
        List<String> problems() {
            var problems = new ArrayList<String>();
            if (summary == null || summary.isBlank()) problems.add("summary is empty");
            if (change == null || change.isBlank()) problems.add("change is empty: show the code as it should be");
            return problems;
        }
    }
}
