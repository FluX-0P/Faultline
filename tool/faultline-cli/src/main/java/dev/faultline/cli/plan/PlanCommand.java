package dev.faultline.cli.plan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

import com.github.javaparser.ast.body.TypeDeclaration;
import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.llm.Asker;
import dev.faultline.cli.llm.Prompt;
import dev.faultline.cli.llm.Schema;
import dev.faultline.cli.scan.Risks;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.Endpoint;
import dev.faultline.cli.source.JavaSources;
import dev.faultline.cli.source.JavaSources.Method;
import dev.faultline.cli.source.RequestShape;
import dev.faultline.cli.source.RequestShapes;
import dev.faultline.cli.workload.RequestTemplate;
import dev.faultline.cli.understand.AppMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.core.JacksonException;

@Command(name = "plan", mixinStandardHelpOptions = true,
    description = {"Turn the app map and a risk into a test plan, show it, and ask you to approve it: .faultline/<app>/plan.yaml.",
        "Each risky pattern has its own fault, placed where Faultline finds the risky code; the LLM drafts the requests",
        "and the rules the data must keep. Approve it as it is, edit the file, or review it: keep or drop each fault, check",
        "and rule. Nothing is written until you approve it; the exit code is 1 if you don't."})
public final class PlanCommand implements Callable<Integer> {
    static final String SYSTEM = """
        You draft the test traffic and the data rules for a Faultline run. Faultline sends many requests to the app's
        HTTP API while it injects a fault at the risky code, then checks that each request's effect is in the database
        exactly once, and that your rules hold.

        Answer with JSON that follows the schema:
        - journey: the journey from the app map whose steps reach the risky code.
        - request: the HTTP call that starts that journey, with method and path exactly as in the endpoint list.
          body is the JSON request body as a template, with the fields its request body lists and no others, each of
          its type and within its limits. Use only these placeholders:
            ${seq}          the request's number in the run: 0, 1, 2 ...
            ${seed}         the run's seed
            ${int:MIN:MAX}  a random whole number from MIN to MAX, both included
          The field that identifies the request must be unique per request and per run, so build it from both
          ${seed} and ${seq}, such as "ord-${seed}-${seq}". Every other value must be accepted by the app on a fresh
          database: refer only to rows the migrations or the test data create (such as "cust-${int:1:50}" when they
          insert cust-1 to cust-50), and keep numbers in a realistic range. Numbers are not quoted.
        - correlateBy: the request body field that identifies the request.
        - effect: the table where processing one request leaves exactly one row, and its column that holds the
          correlateBy value.
        - reset: SQL that puts the database back to its starting state before a run. TRUNCATE every table the
          journey's code inserts into, and UPDATE the rows it changes back to the values the migrations set.
          Only TRUNCATE, DELETE and UPDATE statements, one per item, without semicolons.
        - rules: what the data must keep once the app has processed every request, each as one SELECT that returns
          the rows breaking it, so a correct run returns none. Think of what the journey promises: amounts that must
          add up across tables, rows that must exist once per request, totals that must match the rows they sum. Write
          one that would catch the risk's outcome when a lost or doubled effect row would not show it. Use only the
          tables and columns you are shown. Each rule must hold for any mix of requests, starting from the reset
          database: Faultline drops a rule that fails on a run without the fault. At most five; none that repeat a
          check Faultline already makes.
        - why: one or two sentences on how this traffic reaches the risky code.
        """;
    static final Plan.Confirm CONFIRM = new Plan.Confirm(2, 1, 2);
    static final int MAX_RULES = 5;
    static final int BODY_SAMPLES = 20;
    /** The fake provider's ledger, emptied before every run when the deployment has the stub. */
    static final String STUB_RESET = "TRUNCATE stub.charges";
    static final String STUB_LEDGER = """
        create table stub.charges (
          id              bigserial primary key,
          payment_id      text not null,
          account_id      text,
          amount_minor    bigint not null,
          idempotency_key text unique,
          created_at      timestamptz not null default clock_timestamp()
        );""";
    private static final Pattern INSERT = Pattern.compile("(?i)insert\\s+into\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern UPDATE = Pattern.compile("(?i)\\bupdate\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s+set\\b");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern TABLE_REF = Pattern.compile("(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)");
    private static final Pattern CTE = Pattern.compile("(?i)(?:\\bwith|,)\\s*(?:recursive\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s+as\\s*\\(");
    private static final Pattern KEBAB = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern CREATE_BODY = Pattern.compile("(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z_][A-Za-z0-9_.]*)\\s*\\((.*?)\\)\\s*;");
    private static final Pattern ADD_COLUMN = Pattern.compile(
        "(?i)alter\\s+table\\s+(?:if\\s+exists\\s+)?([A-Za-z_][A-Za-z0-9_.]*)\\s+add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?([A-Za-z_][A-Za-z0-9_]*)");
    private static final Set<String> CONSTRAINTS = Set.of("constraint", "primary", "foreign", "unique", "check", "exclude", "like");
    private static final Pattern TABLE_ALIAS = Pattern.compile(
        "(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)(?:\\s+(?:as\\s+)?([A-Za-z_][A-Za-z0-9_]*))?");
    private static final Pattern COLUMN_REF = Pattern.compile("(?i)\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\b(?!\\s*\\()");
    private static final Set<String> SQL_WORDS = Set.of("where", "join", "on", "group", "order", "left", "right", "inner", "outer", "full",
        "cross", "natural", "limit", "offset", "having", "union", "except", "intersect", "using", "window", "lateral");

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "APP_FOLDER", description = "The app's folder, for example demo-app")
    Path appDir;

    @Option(names = "--risk", paramLabel = "ID", description = "Plan for this risk from risks.json (default: the highest-ranked one Faultline can test)")
    String riskId;

    @Option(names = "--compose", paramLabel = "FILE", description = "Run the app as the services of this Compose file, such as deploy/docker-compose.yml (default: from its jar)")
    String compose;

    @Option(names = "--profile", description = "The Compose profile the app's services are in, such as demo")
    String profile;

    @Option(names = "--yes", description = "Approve the plan without asking")
    boolean yes;

    /**
     * What Faultline reads from the app, its deployment and its hints rather than asking the LLM. {@code shapes}: each
     * endpoint's request body; {@code auth} and {@code seedFile} (may be null) come from the hints.
     */
    record Facts(Deployment deployment, String jar, String dbUrl, String dbUser, String dbPassword, String bootstrap, String topic, String group,
                 Map<String, RequestShape> shapes, Plan.Auth auth, String seedFile) {
        String url() {
            return deployment.url();
        }
    }

    /** Where a risk is tested from: the listener that receives the journey's messages, its topic, and the template's test. */
    record Aim(Risk risk, Method listener, String topic, FaultTemplate.Test test) {}

    /** A plan drawn up for a risk, the model that drafted it, and what its fault does. */
    public record Drawn(Plan plan, String model, String does) {}

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
        // the plan runs the app with the settings the scan judged it with
        var settings = risks.settings() == null ? Map.<String, String>of() : risks.settings();
        App app = App.read(appDir).withSettings(settings);
        Deployment deployment = deployment(app);

        Risk risk;
        if (riskId != null) {
            risk = risks.risks().stream().filter(r -> r.id().equals(riskId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no risk " + riskId + " in risks.json (risks: "
                    + risks.risks().stream().map(Risk::id).collect(Collectors.joining(", ")) + ")"));
        } else {
            risk = null;
            for (Risk r : risks.risks()) {
                try {
                    aim(app, map, r, deployment);
                    risk = r;
                    break;
                } catch (IllegalStateException e) {
                    Out.line(out, "skip      %s %s at %s: %s", r.id(), r.pattern(), r.location(), e.getMessage());
                }
            }
            if (risk == null) throw new IllegalStateException("Faultline cannot test any of the risks in risks.json this way (see above)");
        }

        var asker = Asker.fromSettings(message -> Out.line(out, "          %s", message));
        Drawn drawn = draw(app, map, risk, deployment, settings, Hints.read(appDir), asker, out);
        Path file = dir.resolve(Workspace.PLAN);
        var header = List.of(
            "Faultline plan for %s, drafted from app-map.json and risks.json with %s.".formatted(app.name(), drawn.model()),
            "Tests %s %s at %s: %s.".formatted(risk.id(), risk.pattern(), risk.location(), drawn.does()),
            "Rules with drafted: true are the LLM's; they count once a run without the fault has passed them.",
            "Change anything you like, then run it with:",
            "  ./faultline run " + Out.path(file));
        return approve(out, drawn.plan(), file, header);
    }

    private Deployment deployment(App app) throws Exception {
        int port = Integer.parseInt(app.config().get("server.port").orElse("8080"));
        if (compose == null) {
            if (profile != null) throw new IllegalArgumentException("--profile needs --compose");
            return Deployment.jar("http://localhost:" + port);
        }
        return Deployment.compose(compose, profile, appDir, port);
    }

    /**
     * A plan for one risk, without asking anyone to approve it: the template's fault at the risky code, and the LLM's
     * requests and rules, checked against the code.
     *
     * @param settings app settings the plan runs the app with, on top of its configuration
     * @param hints the app's faultline/hints.yaml: auth for the requests, and test data
     */
    public static Drawn draw(App app, AppMap map, Risk risk, Deployment deployment, Map<String, String> settings, Hints hints, Asker asker,
                             PrintWriter out) throws Exception {
        Aim aim = aim(app, map, risk, deployment);
        Facts facts = facts(app, deployment, aim, hints);
        Out.line(out, "plan      %s %s at %s: %s", risk.id(), risk.pattern(), risk.location(), aim.test().does());
        if (hints.auth() == null && usesSpringSecurity(app)) {
            Out.line(out, "warning   the app uses Spring Security and %s has no auth: its requests may be refused (401). "
                + "Add auth: {header: Authorization, value: \"Bearer ${env:NAME}\"}", Hints.FILE);
        }
        Out.line(out, "llm       asking %s to draft the traffic and the rules ...", asker.model());
        var answer = asker.ask(SYSTEM, prompt(app, map, aim, deployment, facts), Schema.load("plan-draft"), PlanDraft.class,
            draft -> problems(draft, app, map, aim, facts, settings));
        Out.line(out, answer.cached() ? "          cached answer, no call made" : "          answered in %.1fs".formatted(answer.took().toMillis() / 1000.0));
        Out.line(out, "          %s", answer.value().why());
        for (var rule : answer.value().rules()) Out.line(out, "rule      %s: %s", rule.id(), rule.why());
        return new Drawn(build(answer.value(), app.name(), aim, facts, settings), answer.model(), aim.test().does());
    }

    /** Whether the build pulls in Spring Security, so the API most likely wants credentials. */
    static boolean usesSpringSecurity(App app) {
        return app.files().buildFiles().stream().anyMatch(f -> f.text().contains("spring-boot-starter-security")
            || f.text().contains("spring-boot-starter-oauth2-resource-server") || f.text().contains("spring-security-"));
    }

    /** @throws IllegalStateException when the risk cannot be tested, saying why */
    static Aim aim(App app, AppMap map, Risk risk, Deployment deployment) {
        Method listener = listenerFor(app, map, risk);
        Endpoint endpoint = app.code().endpoints().stream()
            .filter(e -> e.kind() == Endpoint.Kind.KAFKA && e.handler().equals(listener.handler())).findFirst().orElseThrow();
        if (endpoint.topics().isEmpty()) throw new IllegalStateException("cannot tell which topic " + listener.handler() + " listens to");
        String topic = endpoint.topics().getFirst();
        return new Aim(risk, listener, topic, FaultTemplate.of(risk, app, deployment, listener, topic));
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
        Plan current = plan;
        boolean onDisk = false;   // whether the file holds the plan as it is now: an edited plan stays exactly as you left it
        boolean valid = true;
        while (true) {
            out.print("Approve? [y / n / edit / review] ");
            out.flush();
            String reply = in.readLine();
            if (reply == null) {
                if (onDisk) Files.deleteIfExists(file);
                Out.line(out, "\nnot approved (no answer): nothing written. --yes approves without asking");
                return Faultline.FAIL;
            }
            switch (reply.strip().toLowerCase(Locale.ROOT)) {
                case "y", "yes" -> {
                    if (!valid) {
                        Out.line(out, "the plan still has problems: edit or review it again, or n to throw it away");
                        continue;
                    }
                    if (!onDisk) current.write(file, header);
                    Out.line(out, "approved  wrote %s", Out.path(file));
                    Out.line(out, "next      ./faultline run %s", Out.path(file));
                    return Faultline.PASS;
                }
                case "n", "no" -> {
                    if (onDisk) Files.deleteIfExists(file);
                    Out.line(out, "not approved: nothing written");
                    return Faultline.FAIL;
                }
                case "e", "edit" -> {
                    if (!onDisk) current.write(file, header);
                    onDisk = true;
                    Editor.open(file);
                    try {
                        current = Plan.read(file);
                        valid = report(out, "the edited plan", current.problems());
                    } catch (IllegalArgumentException e) {
                        valid = false;
                        Out.line(out, "%s", e.getMessage());
                    }
                }
                case "r", "review" -> {
                    if (!valid && onDisk) {
                        Out.line(out, "the edited plan does not read: edit it first");
                        continue;
                    }
                    current = PlanReview.review(current, in, out);
                    if (onDisk) current.write(file, header);
                    Out.line(out, "");
                    Out.line(out, current.toYaml());
                    valid = report(out, "the reviewed plan", current.problems());
                }
                default -> Out.line(out, "answer y, n, edit or review");
            }
        }
    }

    private static boolean report(PrintWriter out, String what, List<String> problems) {
        if (problems.isEmpty()) {
            Out.line(out, "%s is valid", what);
            return true;
        }
        Out.line(out, "%s has problems:", what);
        for (String p : problems) Out.line(out, "  - %s", p);
        return false;
    }

    static Plan build(PlanDraft d, String appName, Aim aim, Facts f, Map<String, String> settings) {
        var test = aim.test();
        var reset = new ArrayList<>(d.reset().stream().map(s -> s.strip().replaceAll(";+$", "").strip()).filter(s -> !s.isEmpty()).toList());
        if (f.deployment().stub() != null && !reset.contains(STUB_RESET)) reset.add(STUB_RESET);
        var args = new LinkedHashMap<>(settings);
        args.putAll(test.settings());
        var rules = new ArrayList<>(test.rules());
        for (var r : d.rules()) rules.add(new Plan.Rule(r.id(), r.sql().strip().replaceAll(";+$", "").strip(), r.why(), true));
        var t = test.traffic();
        var deployment = f.deployment();
        return new Plan(appName, d.journey(), aim.risk(),
            new Plan.Target(f.url(), deployment.compose() == null ? f.jar() : null, deployment.compose(),
                args.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toList(), f.auth()),
            new Plan.Database(f.dbUrl(), f.dbUser(), f.dbPassword(), reset, f.seedFile()),
            new Plan.Kafka(f.bootstrap(), f.topic(), f.group()),
            test.relay(), null, test.stub() ? deployment.stub() : null,
            // the closing newline makes plan.yaml show the body as a | block, so its quotes need no escaping
            new Plan.Traffic(t.count(), t.rate(), null, new Plan.Request(d.request().method(), d.request().path(), d.request().body().strip() + "\n"),
                t.drainTimeout(), t.warmup()),
            new Plan.CorrelateBy(d.correlateBy(), d.effect().table(), d.effect().column()),
            test.downstreamTopic() == null ? null : new Plan.Downstream(test.downstreamTopic(), d.correlateBy()),
            null,
            test.fault(), test.checks(), rules.isEmpty() ? null : rules, test.recoveryWithin(),
            CONFIRM);
    }

    /** Checks the LLM's draft against the code and the app map, and the plan it makes against the plan's own rules. */
    static List<String> problems(PlanDraft d, App app, AppMap map, Aim aim, Facts facts, Map<String, String> settings) {
        if (d.request() == null || d.effect() == null || d.reset() == null || d.rules() == null) {
            return List.of("request, effect, reset and rules must all be present");
        }
        if (d.rules().stream().anyMatch(r -> r.id() == null || r.sql() == null || r.why() == null)) return List.of("every rule needs id, why and sql");
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
        var shape = facts.shapes().get(d.request().method() + " " + d.request().path());
        if (shape == null) shape = facts.shapes().get("ANY " + d.request().path());
        if (shape != null && d.request().body() != null) problems.addAll(bodyProblems(d.request().body(), shape));

        var types = journeyTypes(app, aim.listener());
        Set<String> inserted = tables(app, types, INSERT);
        if (!inserted.isEmpty() && !inserted.contains(d.effect().table().toLowerCase(Locale.ROOT))) {
            problems.add("effect.table '%s' is not a table the journey's code inserts into (%s)".formatted(d.effect().table(), String.join(", ", inserted)));
        }
        for (String table : inserted) {
            if (d.reset().stream().noneMatch(s -> clears(table).matcher(s).find())) {
                problems.add("reset does not empty " + table + ", which the journey's code inserts into: rows left by one run would break the next");
            }
        }
        for (String table : seededTables(app)) {
            if (d.reset().stream().anyMatch(s -> clears(table).matcher(s).find())) {
                problems.add("reset empties " + table + ", which the migrations fill: UPDATE its rows back to the migrations' values instead");
            }
        }
        for (String table : tables(app, types, UPDATE)) {
            if (inserted.contains(table)) continue;
            var setBack = Pattern.compile("(?is)^\\s*update\\s+" + Pattern.quote(table) + "\\b");
            if (d.reset().stream().noneMatch(s -> setBack.matcher(s).find() || clears(table).matcher(s).find())) {
                problems.add("reset does not set " + table + " back, which the journey's code updates: UPDATE it to the values the migrations set");
            }
        }
        problems.addAll(ruleProblems(d.rules(), schema(app, facts.deployment())));
        problems.addAll(build(d, app.name(), aim, facts, settings).problems());
        return problems;
    }

    /**
     * Whether the bodies the template makes fit the endpoint's request body: 20 of them, so ${int:MIN:MAX} ranges are
     * tried too. A template that does not even parse is left to Plan#problems, which says why.
     */
    static List<String> bodyProblems(String body, RequestShape shape) {
        RequestTemplate template;
        try {
            template = RequestTemplate.parse(body);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        var problems = new LinkedHashSet<String>();
        var random = new Random(BODY_SAMPLES);
        for (int seq = 0; seq < BODY_SAMPLES && problems.size() < 10; seq++) {
            String rendered = template.render(42, seq, random);
            try {
                problems.addAll(shape.problems(Json.MAPPER.readTree(rendered)));
            } catch (JacksonException e) {
                return List.of();   // not JSON: Plan#problems says so
            }
        }
        return problems.stream().map(p -> "traffic.request.body: " + p + " (request body from " + shape.source() + ")").toList();
    }

    /**
     * The drafted rules' own problems; Plan#problems checks their SQL is one SELECT and their IDs are unique. Tables must
     * exist, and so must every column named through a table or its alias (m.balance_minor), since SQL that does not run
     * proves nothing.
     */
    static List<String> ruleProblems(List<PlanDraft.Rule> rules, Map<String, Set<String>> schema) {
        var tables = new TreeSet<>(schema.keySet());
        var problems = new ArrayList<String>();
        if (rules.size() > MAX_RULES) problems.add("at most " + MAX_RULES + " rules, not " + rules.size());
        var checks = new HashSet<>(Plan.DATA_CHECKS);
        checks.addAll(List.of(Plan.PER_KEY_ORDER, Plan.NO_LOST_DOWNSTREAM, Plan.RECOVERY_WITHIN, Plan.FAULTS_FIRED));
        for (var rule : rules) {
            if (rule.id() == null || !KEBAB.matcher(rule.id()).matches()) {
                problems.add("rule id '" + rule.id() + "' must be kebab-case, such as stock-never-negative");
            } else if (checks.contains(rule.id())) {
                problems.add("rule id " + rule.id() + " is the name of one of Faultline's checks: give the rule a name of its own");
            }
            if (rule.why() == null || rule.why().isBlank()) problems.add("rule " + rule.id() + ": why is empty");
            if (rule.sql() == null) continue;
            String sql = rule.sql().toLowerCase(Locale.ROOT);
            var ctes = CTE.matcher(sql).results().map(m -> m.group(1)).collect(Collectors.toSet());
            var unknown = TABLE_REF.matcher(sql).results().map(m -> m.group(1))
                .filter(t -> !tables.contains(t) && !ctes.contains(t)).distinct().toList();
            if (!unknown.isEmpty()) {
                problems.add("rule %s: %s %s not a table (tables: %s)".formatted(rule.id(), String.join(", ", unknown),
                    unknown.size() == 1 ? "is" : "are", String.join(", ", tables)));
                continue;
            }
            var aliases = new LinkedHashMap<String, String>();
            for (var m : TABLE_ALIAS.matcher(sql).results().toList()) {
                String table = m.group(1);
                aliases.put(table, table);
                if (table.contains(".")) aliases.put(table.substring(table.indexOf('.') + 1), table);
                if (m.group(2) != null && !SQL_WORDS.contains(m.group(2))) aliases.put(m.group(2), table);
            }
            var missing = new LinkedHashSet<String>();
            for (var m : COLUMN_REF.matcher(sql).results().toList()) {
                String table = aliases.get(m.group(1));
                var columns = table == null ? null : schema.get(table);
                if (columns != null && !columns.isEmpty() && !columns.contains(m.group(2))) {
                    missing.add("%s has no column %s (its columns: %s)".formatted(table, m.group(2), String.join(", ", columns)));
                }
            }
            missing.forEach(p -> problems.add("rule " + rule.id() + ": " + p));
        }
        return problems;
    }

    /**
     * The tables of the migrations with their columns, and the fake provider's ledger when the deployment has it. Columns
     * come from create table and alter table ... add column; a table whose columns cannot be read has an empty set.
     */
    static Map<String, Set<String>> schema(App app, Deployment deployment) {
        var schema = new TreeMap<String, Set<String>>();
        var sql = new ArrayList<String>();
        app.files().migrations().forEach(f -> sql.add(f.text()));
        if (deployment.stub() != null) sql.add(STUB_LEDGER);
        for (String text : sql) {
            CREATE_TABLE.matcher(text).results().forEach(m -> schema.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), new TreeSet<>()));
            for (var m : CREATE_BODY.matcher(text).results().toList()) {
                var columns = schema.computeIfAbsent(m.group(1).toLowerCase(Locale.ROOT), k -> new TreeSet<>());
                for (String item : topLevel(m.group(2))) {
                    var first = item.strip().split("\\s+")[0].toLowerCase(Locale.ROOT).replace("\"", "");
                    if (!first.isEmpty() && !CONSTRAINTS.contains(first)) columns.add(first);
                }
            }
            ADD_COLUMN.matcher(text).results().forEach(m -> schema.computeIfAbsent(m.group(1).toLowerCase(Locale.ROOT), k -> new TreeSet<>())
                .add(m.group(2).toLowerCase(Locale.ROOT)));
        }
        return schema;
    }

    /** The comma-separated items of a create table body, ignoring commas inside parentheses such as numeric(10, 2). */
    private static List<String> topLevel(String body) {
        var items = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                items.add(body.substring(start, i));
                start = i + 1;
            }
        }
        items.add(body.substring(start));
        return items;
    }

    /** Tables the migrations insert rows into, such as accounts the app needs from the start. */
    static Set<String> seededTables(App app) {
        var tables = new TreeSet<String>();
        for (var f : app.files().migrations()) INSERT.matcher(f.text()).results().forEach(m -> tables.add(m.group(1).toLowerCase(Locale.ROOT)));
        return tables;
    }

    private static Pattern clears(String table) {
        return Pattern.compile("(?is)^\\s*(truncate|delete\\s+from)\\b.*\\b" + Pattern.quote(table) + "\\b");
    }

    /** Tables named by {@code statement} anywhere in the code of these types. */
    private static Set<String> tables(App app, List<TypeDeclaration<?>> types, Pattern statement) {
        var tables = new TreeSet<String>();
        for (var type : types) {
            var m = statement.matcher(app.code().source(type));
            while (m.find()) tables.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return tables;
    }

    /**
     * The Kafka listener the traffic arrives at: one that reads a topic the HTTP endpoints send to (the plan waits for
     * its lag), else any. Among them, the risk itself or one that calls its way to it comes first, then one the app
     * map's journeys go through. The fault itself goes where the risk is.
     */
    static Method listenerFor(App app, AppMap map, Risk risk) {
        var listeners = app.code().kafkaListeners();
        if (listeners.isEmpty()) throw new IllegalStateException("the app has no Kafka listener: Faultline tests apps that process Kafka messages");
        Set<String> sent = new HashSet<>();
        for (var e : httpEndpoints(app)) {
            app.code().method(e.handler()).ifPresent(h -> Stream.concat(app.code().withOwnCallees(h).stream(), app.code().calledMethods(h).stream())
                .forEach(m -> sent.addAll(app.code().sentTopics(m))));
        }
        Set<String> entry = app.code().endpoints().stream()
            .filter(e -> e.kind() == Endpoint.Kind.KAFKA && e.topics().stream().anyMatch(sent::contains)).map(Endpoint::handler).collect(Collectors.toSet());
        Set<String> inJourneys = map.journeys().stream().flatMap(j -> j.steps().stream()).map(AppMap.Step::handler).collect(Collectors.toSet());
        Comparator<Method> order = Comparator.<Method, Boolean>comparing(l -> !l.handler().equals(risk.location()) && !reaches(app.code(), l, risk.location()))
            .thenComparing(l -> !inJourneys.contains(l.handler()));
        return listeners.stream().filter(l -> entry.isEmpty() || entry.contains(l.handler())).sorted(order).findFirst().orElseThrow();
    }

    /** Whether the method calls its way to {@code target}, through the app's own methods, a few calls deep. */
    static boolean reaches(JavaSources code, Method from, String target) {
        var seen = new HashSet<String>();
        var frontier = List.of(from);
        for (int depth = 0; depth < 5 && !frontier.isEmpty(); depth++) {
            var next = new ArrayList<Method>();
            for (var m : frontier) {
                for (var callee : Stream.concat(code.calledMethods(m).stream(), code.withOwnCallees(m).stream().skip(1)).toList()) {
                    if (callee.handler().equals(target)) return true;
                    if (seen.add(callee.handler())) next.add(callee);
                }
            }
            frontier = next;
        }
        return false;
    }

    /** The listener's class, the classes it calls, and the classes those call, a few levels deep: where the journey's work happens. */
    static List<TypeDeclaration<?>> journeyTypes(App app, Method listener) {
        var types = new LinkedHashMap<String, TypeDeclaration<?>>();
        types.put(listener.owner().getNameAsString(), listener.owner());
        var frontier = List.of(listener);
        for (int depth = 0; depth < 4 && !frontier.isEmpty(); depth++) {
            var next = new ArrayList<Method>();
            for (var m : frontier) {
                for (var type : app.code().calledTypes(m)) {
                    if (types.putIfAbsent(type.getNameAsString(), type) == null) {
                        app.code().methods().stream().filter(x -> x.owner() == type).forEach(next::add);
                    }
                }
            }
            frontier = next;
        }
        return List.copyOf(types.values());
    }

    static String prompt(App app, AppMap map, Aim aim, Deployment deployment, Facts facts) throws IOException {
        var risk = aim.risk();
        var risky = app.code().method(risk.location())
            .orElseThrow(() -> new IllegalStateException(risk.location() + " is no longer in the code: run ./faultline scan again"));
        var http = httpEndpoints(app);
        var handlerTypes = new LinkedHashMap<String, TypeDeclaration<?>>();
        for (var e : http) {
            app.code().method(e.handler()).ifPresent(m -> {
                handlerTypes.putIfAbsent(m.owner().getNameAsString(), m.owner());
                app.code().requestBodyTypes(m).forEach(t -> handlerTypes.putIfAbsent(t.getNameAsString(), t));
            });
        }
        var journeyTypes = new LinkedHashMap<String, TypeDeclaration<?>>();
        journeyTypes(app, aim.listener()).forEach(t -> journeyTypes.put(t.getNameAsString(), t));
        journeyTypes.putIfAbsent(risky.owner().getNameAsString(), risky.owner());
        var test = aim.test();
        var made = new ArrayList<>(test.checks());
        test.rules().forEach(r -> made.add(r.id() + " (" + r.sql() + ")"));
        record MapView(String summary, List<AppMap.EntryPoint> entryPoints, List<AppMap.Journey> journeys, List<AppMap.DataStore> dataStores) {}

        var prompt = new Prompt()
            .line("Risk to test: %s %s \"%s\" at %s%s. %s".formatted(risk.id(), risk.pattern(), risk.title(), risk.location(),
                risk.outcome() == null ? "" : ", where the outcome would be: " + risk.outcome(), risk.why()))
            .line("The fault: Faultline will %s. The checks it already makes: %s.".formatted(test.does(), String.join(", ", made)))
            .code("App map", "json", Json.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new MapView(map.summary(), map.entryPoints(), map.journeys(), map.dataStores())))
            .section("HTTP endpoints that take a request body", http.stream().map(e -> "- " + e.name() + " -> " + e.handler()).collect(Collectors.joining("\n")))
            .section("Their request bodies", http.stream().filter(e -> facts.shapes().containsKey(e.name()))
                .map(e -> "### %s (from %s)\n\n%s".formatted(e.name(), facts.shapes().get(e.name()).source(), facts.shapes().get(e.name()).describe()))
                .collect(Collectors.joining("\n\n")))
            .code("Their handlers and request types", "java", handlerTypes.values().stream().map(app.code()::source).collect(Collectors.joining("\n\n")))
            .code("The listener " + aim.listener().handler() + ", the classes its work goes through, and the risky code", "java",
                journeyTypes.values().stream().map(app.code()::source).collect(Collectors.joining("\n\n")))
            .files("Database migrations", app.files().migrations(), "sql");
        if (facts.seedFile() != null) {
            prompt.code("Test data Faultline loads after every reset (" + facts.seedFile() + ")", "sql", Files.readString(Path.of(facts.seedFile()), StandardCharsets.UTF_8));
        }
        if (deployment.stub() != null) {
            prompt.code("The fake payment provider's ledger: one row per charge it made, in the same database", "sql", STUB_LEDGER);
        }
        return prompt.toString();
    }

    private static List<Endpoint> httpEndpoints(App app) {
        return app.code().endpoints().stream()
            .filter(e -> e.kind() == Endpoint.Kind.HTTP && Set.of("POST", "PUT", "PATCH", "ANY").contains(e.method()))
            .toList();
    }

    static Facts facts(App app, Deployment deployment, Aim aim) throws Exception {
        return facts(app, deployment, aim, Hints.NONE);
    }

    static Facts facts(App app, Deployment deployment, Aim aim, Hints hints) throws Exception {
        var listener = app.code().endpoints().stream()
            .filter(e -> e.kind() == Endpoint.Kind.KAFKA && e.handler().equals(aim.listener().handler())).findFirst().orElseThrow();
        var config = app.config();
        String group = listener.groupId() != null ? listener.groupId() : config.get("spring.kafka.consumer.group-id")
            .orElseThrow(() -> new IllegalStateException("cannot tell the consumer group of " + aim.listener().handler()));
        String dbUrl = config.get("spring.datasource.url")
            .orElseThrow(() -> new IllegalStateException("the app's configuration has no spring.datasource.url"));
        return new Facts(deployment, deployment.compose() == null ? jar(app) : null, dbUrl,
            config.get("spring.datasource.username").orElse(""), config.get("spring.datasource.password").orElse(""),
            config.get("spring.kafka.bootstrap-servers").orElse("localhost:9092"), aim.topic(), group,
            RequestShapes.of(app), hints.auth(), hints.seedFile(app.dir()));
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

    public static <T> T readJson(Path file, Class<T> type, String command) {
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
