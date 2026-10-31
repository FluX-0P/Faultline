package dev.faultline.cli.plan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

import com.github.javaparser.ast.body.TypeDeclaration;
import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.llm.Asker;
import dev.faultline.cli.llm.Prompt;
import dev.faultline.cli.llm.Schema;
import dev.faultline.cli.scan.Risks;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.Endpoint;
import dev.faultline.cli.understand.AppMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.core.JacksonException;

@Command(name = "plan", mixinStandardHelpOptions = true,
    description = {"Turn the app map and the most confident risk into a test plan, show it, and ask you to approve it:",
        ".faultline/<app>/plan.yaml. The plan says what traffic to send, which fault to inject and what to check.",
        "Nothing is written until you approve it; the exit code is 1 if you don't."})
public final class PlanCommand implements Callable<Integer> {
    static final String SYSTEM = """
        You draft the test traffic for a Faultline run. Faultline sends many requests to the app's HTTP API while it
        injects a fault at the risky code, then checks that each request's effect is in the database exactly once.

        Answer with JSON that follows the schema:
        - journey: the journey from the app map whose steps reach the risky code.
        - request: the HTTP call that starts that journey, with method and path exactly as in the endpoint list.
          body is the JSON request body as a template. Use only these placeholders:
            ${seq}          the request's number in the run: 0, 1, 2 ...
            ${seed}         the run's seed
            ${int:MIN:MAX}  a random whole number from MIN to MAX, both included
          The field that identifies the request must be unique per request and per run, so build it from both
          ${seed} and ${seq}, such as "ord-${seed}-${seq}". Every other value must be accepted by the app on a fresh
          database: refer only to rows the migrations create (such as "cust-${int:1:50}" when the migrations insert
          cust-1 to cust-50), and keep numbers in a realistic range. Numbers are not quoted.
        - correlateBy: the request body field that identifies the request.
        - effect: the table where processing one request leaves exactly one row, and its column that holds the
          correlateBy value.
        - reset: SQL that puts the database back to its starting state before a run. TRUNCATE every table the
          journey's code inserts into, and UPDATE the rows it changes back to the values the migrations set.
          Only TRUNCATE, DELETE and UPDATE statements, one per item, without semicolons.
        - why: one or two sentences on how this traffic reaches the risky code.
        """;
    // with a crash every 300 acknowledgments the app is down ~7s each time; 3000 requests leave room for all three
    static final int COUNT = 3000;
    static final int RATE = 100;
    static final long NTH_HIT = 300;
    static final int TIMES = 3;
    // two runs with the fault must both break the data, and one run without it must not
    static final Plan.Confirm CONFIRM = new Plan.Confirm(2, 1, 2);
    private static final Pattern INSERT = Pattern.compile("(?i)insert\\s+into\\s+([A-Za-z_][A-Za-z0-9_.]*)");

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "APP_FOLDER", description = "The app's folder, for example demo-app")
    Path appDir;

    @Option(names = "--risk", paramLabel = "ID", description = "Plan for this risk from risks.json (default: the first, most confident one)")
    String riskId;

    @Option(names = "--yes", description = "Approve the plan without asking")
    boolean yes;

    /** What Faultline reads from the app itself rather than asking the LLM. */
    record Facts(String url, String jar, String dbUrl, String dbUser, String dbPassword, String bootstrap, String topic, String group) {}

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        Path dir = Workspace.forApp(appDir);
        AppMap map = readJson(dir.resolve(Workspace.APP_MAP), AppMap.class, "understand");
        Risks risks = readJson(dir.resolve(Workspace.RISKS), Risks.class, "scan");
        if (risks.risks().isEmpty()) {
            Out.line(out, "plan      nothing to plan: the scan flagged no risks in %s", appDir);
            return Faultline.PASS;
        }
        Risk risk = riskId == null ? risks.risks().getFirst() : risks.risks().stream().filter(r -> r.id().equals(riskId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no risk " + riskId + " in risks.json (risks: "
                + risks.risks().stream().map(Risk::id).collect(Collectors.joining(", ")) + ")"));
        if (!risk.pattern().equals("P1")) throw new IllegalArgumentException("only P1 risks can be planned for now, not " + risk.pattern());

        App app = App.read(appDir);
        Facts facts = facts(app, risk);
        Out.line(out, "plan      %s %s at %s: crash just before acknowledge(), on every %dth acknowledgment, %d times",
            risk.id(), risk.pattern(), risk.location(), NTH_HIT, TIMES);

        var asker = Asker.fromSettings(message -> Out.line(out, "          %s", message));
        Out.line(out, "llm       asking %s to draft the traffic ...", asker.model());
        var answer = asker.ask(SYSTEM, prompt(app, map, risk), Schema.load("plan-draft"), PlanDraft.class,
            draft -> problems(draft, app, map, risk, facts));
        Out.line(out, answer.cached() ? "          cached answer, no call made" : "          answered in %.1fs".formatted(answer.took().toMillis() / 1000.0));
        Out.line(out, "          %s", answer.value().why());

        Plan plan = build(answer.value(), app.name(), risk, facts);
        Path file = dir.resolve(Workspace.PLAN);
        var header = List.of(
            "Faultline plan for %s, drafted from app-map.json and risks.json with %s.".formatted(app.name(), answer.model()),
            "Tests %s %s at %s. Change anything you like, then run it with:".formatted(risk.id(), risk.pattern(), risk.location()),
            "  ./faultline run " + Out.path(file));
        return approve(out, plan, file, header);
    }

    private int approve(PrintWriter out, Plan plan, Path file, List<String> header) throws IOException, InterruptedException {
        Out.line(out, "");
        for (String line : header) Out.line(out, "# %s", line);
        Out.line(out, plan.toYaml());
        if (yes) {
            plan.write(file, header);
            Out.line(out, "approved  (--yes) wrote %s", Out.path(file));
            Out.line(out, "next      ./faultline run %s", Out.path(file));
            return Faultline.PASS;
        }

        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        boolean edited = false;
        boolean valid = true;
        while (true) {
            out.print("Approve? [y / n / edit] ");
            out.flush();
            String reply = in.readLine();
            if (reply == null) {
                if (edited) Files.deleteIfExists(file);
                Out.line(out, "\nnot approved (no answer): nothing written. --yes approves without asking");
                return Faultline.FAIL;
            }
            switch (reply.strip().toLowerCase(Locale.ROOT)) {
                case "y", "yes" -> {
                    if (!valid) {
                        Out.line(out, "the plan still has problems: edit it again, or n to throw it away");
                        continue;
                    }
                    if (!edited) plan.write(file, header);   // an edited plan stays exactly as you left it
                    Out.line(out, "approved  wrote %s", Out.path(file));
                    Out.line(out, "next      ./faultline run %s", Out.path(file));
                    return Faultline.PASS;
                }
                case "n", "no" -> {
                    if (edited) Files.deleteIfExists(file);
                    Out.line(out, "not approved: nothing written");
                    return Faultline.FAIL;
                }
                case "e", "edit" -> {
                    if (!edited) plan.write(file, header);
                    edited = true;
                    Editor.open(file);
                    try {
                        Plan changed = Plan.read(file);
                        var problems = changed.problems();
                        valid = problems.isEmpty();
                        if (valid) {
                            Out.line(out, "the edited plan is valid");
                        } else {
                            Out.line(out, "the edited plan has problems:");
                            for (String p : problems) Out.line(out, "  - %s", p);
                        }
                    } catch (IllegalArgumentException e) {
                        valid = false;
                        Out.line(out, "%s", e.getMessage());
                    }
                }
                default -> Out.line(out, "answer y, n or edit");
            }
        }
    }

    static Plan build(PlanDraft d, String appName, Risk risk, Facts f) {
        var reset = d.reset().stream().map(s -> s.strip().replaceAll(";+$", "").strip()).filter(s -> !s.isEmpty()).toList();
        return new Plan(appName, d.journey(), risk,
            new Plan.Target(f.url(), f.jar(), null, List.of()),
            new Plan.Database(f.dbUrl(), f.dbUser(), f.dbPassword(), reset),
            new Plan.Kafka(f.bootstrap(), f.topic(), f.group()),
            null, null, null,
            // the closing newline makes plan.yaml show the body as a | block, so its quotes need no escaping
            new Plan.Traffic(COUNT, RATE, null, new Plan.Request(d.request().method(), d.request().path(), d.request().body().strip() + "\n"), null),
            new Plan.CorrelateBy(d.correlateBy(), d.effect().table(), d.effect().column()),
            null, null,
            new Plan.Fault(null, "before-ack", null, "crash", NTH_HIT, TIMES, null, null, null),
            List.of("no-lost", "no-duplicates", "no-unexpected", Plan.FAULTS_FIRED),
            null, null,
            CONFIRM);
    }

    /** Checks the LLM's draft against the code and the app map, and the plan it makes against the plan's own rules. */
    static List<String> problems(PlanDraft d, App app, AppMap map, Risk risk, Facts facts) {
        if (d.request() == null || d.effect() == null || d.reset() == null) return List.of("request, effect and reset must all be present");
        var problems = new ArrayList<String>();
        var journeys = map.journeys().stream().map(AppMap.Journey::name).toList();
        if (!journeys.contains(d.journey())) problems.add("journey '" + d.journey() + "' is not in the app map (journeys: " + String.join(", ", journeys) + ")");

        var http = httpEndpoints(app);
        boolean known = http.stream().anyMatch(e -> e.path().equals(d.request().path())
            && (e.method().equals(d.request().method()) || e.method().equals("ANY")));
        if (!known) {
            problems.add("%s %s is not an endpoint in the code (endpoints: %s)".formatted(d.request().method(), d.request().path(),
                http.stream().map(Endpoint::name).collect(Collectors.joining(", "))));
        }

        Set<String> inserted = insertedTables(app, risk);
        if (!inserted.isEmpty() && !inserted.contains(d.effect().table().toLowerCase(Locale.ROOT))) {
            problems.add("effect.table '%s' is not a table the risky code inserts into (%s)".formatted(d.effect().table(), String.join(", ", inserted)));
        }
        for (String table : inserted) {
            var clears = Pattern.compile("(?is)^\\s*(truncate|delete\\s+from)\\b.*\\b" + Pattern.quote(table) + "\\b");
            if (d.reset().stream().noneMatch(s -> clears.matcher(s).find())) {
                problems.add("reset does not empty " + table + ", which the risky code inserts into: rows left by one run would break the next");
            }
        }
        problems.addAll(build(d, app.name(), risk, facts).problems());
        return problems;
    }

    /** Tables named in "insert into" statements in the risky listener and the classes it calls. */
    static Set<String> insertedTables(App app, Risk risk) {
        var tables = new TreeSet<String>();
        app.code().method(risk.location()).ifPresent(listener -> {
            var types = new ArrayList<TypeDeclaration<?>>(List.of(listener.owner()));
            types.addAll(app.code().calledTypes(listener));
            for (var type : types) {
                var m = INSERT.matcher(app.code().source(type));
                while (m.find()) tables.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        });
        return tables;
    }

    static String prompt(App app, AppMap map, Risk risk) {
        var listener = app.code().method(risk.location())
            .orElseThrow(() -> new IllegalStateException(risk.location() + " is no longer in the code: run ./faultline scan again"));
        var http = httpEndpoints(app);
        var handlerTypes = new LinkedHashMap<String, TypeDeclaration<?>>();
        for (var e : http) {
            app.code().method(e.handler()).ifPresent(m -> {
                handlerTypes.putIfAbsent(m.owner().getNameAsString(), m.owner());
                app.code().requestBodyTypes(m).forEach(t -> handlerTypes.putIfAbsent(t.getNameAsString(), t));
            });
        }
        var riskyTypes = new ArrayList<TypeDeclaration<?>>(List.of(listener.owner()));
        riskyTypes.addAll(app.code().calledTypes(listener));
        record MapView(String summary, List<AppMap.EntryPoint> entryPoints, List<AppMap.Journey> journeys, List<AppMap.DataStore> dataStores) {}

        return new Prompt()
            .line("Risk to test: %s %s \"%s\" at %s. %s".formatted(risk.id(), risk.pattern(), risk.title(), risk.location(), risk.why()))
            .code("App map", "json", Json.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new MapView(map.summary(), map.entryPoints(), map.journeys(), map.dataStores())))
            .section("HTTP endpoints that take a request body", http.stream().map(e -> "- " + e.name() + " -> " + e.handler()).collect(Collectors.joining("\n")))
            .code("Their handlers and request types", "java", handlerTypes.values().stream().map(app.code()::source).collect(Collectors.joining("\n\n")))
            .code("The risky listener and the classes it calls", "java", riskyTypes.stream().map(app.code()::source).collect(Collectors.joining("\n\n")))
            .files("Database migrations", app.files().migrations(), "sql")
            .toString();
    }

    private static List<Endpoint> httpEndpoints(App app) {
        return app.code().endpoints().stream()
            .filter(e -> e.kind() == Endpoint.Kind.HTTP && Set.of("POST", "PUT", "PATCH", "ANY").contains(e.method()))
            .toList();
    }

    static Facts facts(App app, Risk risk) throws Exception {
        Endpoint listener = app.code().endpoints().stream()
            .filter(e -> e.kind() == Endpoint.Kind.KAFKA && e.handler().equals(risk.location()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(risk.location() + " is no longer a Kafka listener in the code: run ./faultline scan again"));
        if (listener.topics().isEmpty()) throw new IllegalStateException("cannot tell which topic " + risk.location() + " listens to");
        var config = app.config();
        String group = listener.groupId() != null ? listener.groupId() : config.get("spring.kafka.consumer.group-id")
            .orElseThrow(() -> new IllegalStateException("cannot tell the consumer group of " + risk.location()));
        String dbUrl = config.get("spring.datasource.url")
            .orElseThrow(() -> new IllegalStateException("the app's configuration has no spring.datasource.url"));
        return new Facts("http://localhost:" + config.get("server.port").orElse("8080"), jar(app), dbUrl,
            config.get("spring.datasource.username").orElse(""), config.get("spring.datasource.password").orElse(""),
            config.get("spring.kafka.bootstrap-servers").orElse("localhost:9092"), listener.topics().getFirst(), group);
    }

    /** The jar in target/, or the name Maven will give it once built. */
    static String jar(App app) throws Exception {
        Path target = app.dir().resolve("target");
        if (Files.isDirectory(target)) {
            try (var files = Files.list(target)) {
                var jars = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jar") && !n.endsWith("-plain.jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar"))
                    .sorted().toList();
                if (jars.size() == 1) return Out.path(target.resolve(jars.getFirst()));
            }
        }
        Path pom = app.dir().resolve("pom.xml");
        if (!Files.isRegularFile(pom)) throw new IllegalStateException("cannot find the app's jar: build it first, so it is in " + Out.path(target));
        Element project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        String version = child(project, "version");
        if (version == null) {
            var parents = project.getElementsByTagName("parent");
            version = parents.getLength() > 0 ? child((Element) parents.item(0), "version") : null;
        }
        return Out.path(target.resolve(child(project, "artifactId") + "-" + version + ".jar"));
    }

    private static String child(Element parent, String name) {
        for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) return e.getTextContent().strip();
        }
        return null;
    }

    private static <T> T readJson(Path file, Class<T> type, String command) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no " + file.getFileName() + " yet: run ./faultline " + command + " first");
        }
        try {
            return Json.MAPPER.readValue(file.toFile(), type);
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot read " + file + ": " + e.getOriginalMessage() + " (run ./faultline " + command + " again)");
        }
    }
}
