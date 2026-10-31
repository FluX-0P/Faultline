package dev.faultline.cli.scan;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.github.javaparser.ast.body.TypeDeclaration;
import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.llm.Asker;
import dev.faultline.cli.llm.Prompt;
import dev.faultline.cli.llm.Schema;
import dev.faultline.cli.scan.Detectors.Candidate;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.JavaSources.Method;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "scan", mixinStandardHelpOptions = true,
    description = {"Find risky code and ask the LLM to judge each candidate: .faultline/<app>/risks.json",
        "Static checks find candidates for patterns P1-P8; the LLM reviews each pattern's candidates in one call.",
        "Risks are ranked by how bad their outcome is (money over lost data over slow recovery), then by confidence."})
public final class ScanCommand implements Callable<Integer> {
    static final String SYSTEM = """
        You review the code of a Spring Boot + Kafka app for Faultline, a tool that crash-tests apps to find lost,
        duplicated or wrong data. A static check found the candidates below for one risky pattern; it flags anything that
        might have the risk, so many candidates are safe. Decide for each one whether the risk is real.

        The pattern: %s

        %s

        Rules:
        - Judge the code as it runs with the configuration shown. A protection that the configuration switches off does
          not count, and a risky path the configuration never takes is not a risk.
        - Addresses of outside services and credentials are often left empty or local in the configuration and set when
          the app is deployed. Assume they are set: an empty URL does not switch an outside call off.
        - Comments were removed. Judge only the code, the configuration and the schema.
        - risky is true only if the failure described can really happen in this app. Name the lines and settings that
          decide it.
        - Give a verdict for every candidate, by its ID.
        """;
    private static final List<String> CONFIDENCE_ORDER = List.of("high", "medium", "low");
    /** Patterns judged on how a consumer copes with the messages it gets; their review also sees the senders. */
    private static final List<String> CONSUMER_SIDE = List.of("P1", "P3");

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "APP_FOLDER", description = "The app's folder, for example demo-app")
    Path appDir;

    @Option(names = "--set", paramLabel = "KEY=VALUE", description = "Judge the app with this setting, on top of its own configuration (repeatable)")
    Map<String, String> settings = new LinkedHashMap<>();

    @Option(names = "--pattern", split = ",", paramLabel = "ID", description = "Only these patterns, such as --pattern P1,P4 (default: all)")
    List<String> patterns = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        for (String p : patterns) {
            if (RiskPattern.byId(p).isEmpty()) throw new IllegalArgumentException("no pattern " + p + ": use P1 to P8");
        }
        Path dir = Workspace.forApp(appDir);
        Path mapFile = dir.resolve(Workspace.APP_MAP);
        String appMap = Files.isRegularFile(mapFile) ? Files.readString(mapFile) : null;
        App app = App.read(appDir).withSettings(settings);

        Risks result = scan(app, settings, patterns, appMap, () -> Asker.fromSettings(message -> Out.line(out, "          %s", message)), out);

        Files.createDirectories(dir);
        Path file = dir.resolve(Workspace.RISKS);
        Json.writePretty(file, result);
        Out.line(out, "wrote     %s", Out.path(file));
        if (appMap == null) Out.line(out, "note      no app-map.json yet: ./faultline understand %s gives the review the app's journeys too", appDir);
        if (!result.risks().isEmpty()) Out.line(out, "next      ./faultline plan %s", appDir);
        return Faultline.PASS;
    }

    /**
     * Finds the candidates (only those of {@code patterns}, if any are given), has the LLM judge each pattern's
     * candidates in one call, and ranks the risky ones. The LLM is only set up if there is something to ask.
     */
    public static Risks scan(App app, Map<String, String> settings, List<String> patterns, String appMap, Supplier<Asker> askers, PrintWriter out) {
        var byPattern = Detectors.byPattern(new Detectors(app.code(), app.config()).all());
        byPattern.keySet().removeIf(p -> !patterns.isEmpty() && !patterns.contains(p));
        Out.line(out, "scan      %d candidate(s): %s%s", byPattern.values().stream().mapToInt(List::size).sum(),
            byPattern.isEmpty() ? "none" : byPattern.entrySet().stream().map(e -> e.getKey() + " " + e.getValue().size()).collect(Collectors.joining(", ")),
            settings.isEmpty() ? "" : ", with " + settings.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" ")));

        var risky = new ArrayList<Risk>();
        var dismissed = new ArrayList<Risk>();
        String model = null;
        if (!byPattern.isEmpty()) {
            var asker = askers.get();
            model = asker.model();
            for (var entry : byPattern.entrySet()) {
                RiskPattern pattern = RiskPattern.byId(entry.getKey()).orElseThrow();
                var candidates = entry.getValue();
                var ids = ids(candidates);
                Out.line(out, "review    %s %s: %d candidate(s), asking %s ...", pattern.id(), pattern.title(), candidates.size(), model);
                var answer = asker.ask(system(pattern), prompt(app, pattern, candidates, appMap), Schema.load("pattern-review"), PatternReview.class,
                    review -> review.problems(List.copyOf(ids.keySet())));
                for (var v : answer.value().verdicts()) {
                    Candidate c = ids.get(v.candidate());
                    Method m = c.method();
                    var risk = new Risk(null, pattern.id(), pattern.title(), m.handler(), m.file(), m.line(), v.confidence(), v.why(), pattern.outcome(), c.saw());
                    (v.risky() ? risky : dismissed).add(risk);
                    Out.line(out, "          %s%-5s %-8s %s (%s)", answer.cached() ? "(cached) " : "", v.risky() ? "RISKY" : "safe", v.confidence(), m.handler(), c.saw());
                }
            }
        }

        risky.sort(ranking());
        var numbered = new ArrayList<Risk>();
        for (var r : risky) {
            numbered.add(new Risk("R" + (numbered.size() + 1), r.pattern(), r.title(), r.location(), r.file(), r.line(), r.confidence(), r.why(),
                r.outcome(), r.saw()));
        }

        Out.line(out, "risks     %d flagged, %d dismissed", numbered.size(), dismissed.size());
        for (var r : numbered) Out.line(out, "          %-4s %s %-8s %-15s %s", r.id(), r.pattern(), r.confidence(), r.outcome(), r.location());
        return new Risks(app.name(), model, settings.isEmpty() ? null : Map.copyOf(settings), numbered, dismissed);
    }

    /** Worst outcome first (money, then lost data, then slow recovery), then the most confident, then by pattern. */
    static Comparator<Risk> ranking() {
        return Comparator.<Risk>comparingInt(r -> -RiskPattern.byId(r.pattern()).map(RiskPattern::severity).orElse(0))
            .thenComparingInt(r -> CONFIDENCE_ORDER.indexOf(r.confidence()))
            .thenComparing(Risk::pattern)
            .thenComparing(Risk::location);
    }

    static String system(RiskPattern pattern) {
        return SYSTEM.formatted(pattern.id() + " " + pattern.title() + ".", pattern.risk());
    }

    /** C1, C2 ... for the pattern's candidates, in the order found. */
    static LinkedHashMap<String, Candidate> ids(List<Candidate> candidates) {
        var ids = new LinkedHashMap<String, Candidate>();
        for (var c : candidates) ids.put("C" + (ids.size() + 1), c);
        return ids;
    }

    /**
     * The candidates, then the code around them: each candidate's class, the app's classes it calls, the classes that
     * call it (two steps back, so a reviewer sees which listener reaches it), with the configuration, the schema and the
     * app map when there is one.
     */
    static String prompt(App app, RiskPattern pattern, List<Candidate> candidates, String appMap) {
        var code = app.code();
        var lines = new StringBuilder();
        var types = new LinkedHashMap<String, TypeDeclaration<?>>();
        ids(candidates).forEach((id, c) -> {
            Method m = c.method();
            lines.append("- %s: %s (%s, line %d): %s%n".formatted(id, m.handler(), m.file(), m.line(), c.saw()));
            var near = new ArrayList<TypeDeclaration<?>>(List.of(m.owner()));
            near.addAll(code.calledTypes(m));
            code.withOwnCallees(m).forEach(x -> near.addAll(code.calledTypes(x)));
            if (c.caller() != null) near.add(c.caller().owner());
            for (var caller : code.callers(m)) {
                near.add(caller.owner());
                code.callers(caller).forEach(cc -> near.add(cc.owner()));
            }
            near.forEach(t -> types.putIfAbsent(t.getNameAsString(), t));
        });
        // whether a message can come twice with different IDs depends on how its sender makes them
        if (CONSUMER_SIDE.contains(pattern.id())) {
            new Detectors(code, app.config()).publishers().forEach(p -> types.putIfAbsent(p.owner().getNameAsString(), p.owner()));
        }
        return new Prompt()
            .line("Pattern %s: %s. Candidates:".formatted(pattern.id(), pattern.title()))
            .line(lines.toString().strip())
            .code("Code (the candidates' classes, the classes they call and the classes that reach them"
                    + (CONSUMER_SIDE.contains(pattern.id()) ? ", and the classes that send the app's messages)" : ")"), "java",
                types.values().stream().map(code::source).collect(Collectors.joining("\n\n")))
            .code("Outline of the whole app", "text", code.outline())
            .code("Spring configuration (flattened)", "properties", app.config().asText())
            .files("Database schema (migrations)", app.files().migrations(), "sql")
            .code("App map (from faultline understand)", "json", appMap)
            .toString();
    }
}
