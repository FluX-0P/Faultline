package dev.faultline.cli.plan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import dev.faultline.cli.app.Environment;
import dev.faultline.cli.llm.Asker;
import dev.faultline.cli.llm.Prompt;
import dev.faultline.cli.llm.Schema;
import dev.faultline.cli.scan.Risks;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.Endpoint;
import dev.faultline.cli.source.JavaSources;
import dev.faultline.cli.source.JavaSources.Method;
import dev.faultline.cli.source.JpaTables;
import dev.faultline.cli.source.RequestShape;
import dev.faultline.cli.source.RequestShapes;
import dev.faultline.cli.source.SpringConfig;
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
    private static final Pattern RESET_TABLE = Pattern.compile("(?i)^\\s*(?:truncate(?:\\s+table)?|delete\\s+from|update)\\s+(?:only\\s+)?([A-Za-z_][A-Za-z0-9_.]*)");
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
     * endpoint's request body; {@code auth} and {@code seedFile} (may be null) come from the hints. A repo of several
     * Spring Boot apps has its {@code databases}, each with its tables, and the {@code urls} of its HTTP endpoints, by
     * "METHOD /path": each answers on a service of its own module. An app of one module has neither, and its one database
     * in {@code dbUrl}, {@code dbUser} and {@code dbPassword}.
     */
    record Facts(Deployment deployment, String jar, String dbUrl, String dbUser, String dbPassword, String bootstrap, String topic, String group,
                 Map<String, RequestShape> shapes, Plan.Auth auth, String seedFile, List<Db> databases, Map<String, String> urls) {
        Facts(Deployment deployment, String jar, String dbUrl, String dbUser, String dbPassword, String bootstrap, String topic, String group,
              Map<String, RequestShape> shapes, Plan.Auth auth, String seedFile) {
            this(deployment, jar, dbUrl, dbUser, dbPassword, bootstrap, topic, group, shapes, auth, seedFile, List.of(), Map.of());
        }

        String url() {
            return deployment.url();
        }

        /** Where the request goes: the service of the endpoint's own module, in a repo of several. */
        String url(Plan.Request request) {
            return urls.getOrDefault(request.method() + " " + request.path(), urls.getOrDefault("ANY " + request.path(), url()));
        }

        boolean severalDatabases() {
            return !databases.isEmpty();
        }

        /** The database a table is in, by its name; empty when none or more than one has it. */
        Optional<Db> databaseOf(String table) {
            String t = table.toLowerCase(Locale.ROOT);
            var found = databases.stream().filter(d -> d.schema().containsKey(t) || d.schema().containsKey("public." + t)).toList();
            return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
        }
    }

    /** A database of a repo of several Spring Boot apps: its name, how Faultline reaches it, its tables and their columns. */
    record Db(String name, String url, String user, String password, Map<String, Set<String>> schema) {}

    /**
     * Where a risk is tested from: the listener that receives the journey's messages, its topic, the template's test, and
     * the deployment as the journey sees it (the listener module's consumers, the fault on the risky module's service).
     */
    record Aim(Risk risk, Method listener, String topic, FaultTemplate.Test test, Deployment deployment) {}

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
        App app = App.read(appDir, Environment.read(dir).orElse(null)).withSettings(settings);
        Deployment deployment = deployment(app, Hints.read(appDir));

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

    private Deployment deployment(App app, Hints hints) throws Exception {
        if (compose == null) {
            if (profile != null) throw new IllegalArgumentException("--profile needs --compose");
            if (app.multiModule()) {
                throw new IllegalArgumentException(app.name() + " has " + app.modules().size() + " Spring Boot apps: run them as the services of its Compose file (plan --compose)");
            }
            return Deployment.jar("http://localhost:" + port(app.config()));
        }
        // doctor's override reaches Kafka and the databases from this machine, for the Compose file it booted
        String override = app.runtime().map(Environment::compose)
            .filter(c -> Path.of(c.file()).toAbsolutePath().normalize().equals(Path.of(compose).toAbsolutePath().normalize()))
            .map(Environment.Compose::override).orElse(null);
        var modules = app.modules().stream().map(m -> new Deployment.Module(m.path(), port(m.config()),
            app.code().kafkaListeners().stream().anyMatch(l -> m.path().equals(l.module())))).toList();
        return Deployment.compose(compose, override, profile, appDir, modules, hints);
    }

    private static int port(SpringConfig config) {
        return Integer.parseInt(config.get("server.port").orElse("8080").strip());
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
        Facts facts = facts(app, aim.deployment(), aim, hints);
        Out.line(out, "plan      %s %s at %s: %s", risk.id(), risk.pattern(), risk.location(), aim.test().does());
        if (hints.auth() == null && usesSpringSecurity(app)) {
            Out.line(out, "warning   the app uses Spring Security and %s has no auth: its requests may be refused (401). "
                + "Add auth: {header: Authorization, value: \"Bearer ${env:NAME}\"}", Hints.FILE);
        }
        Out.line(out, "llm       asking %s to draft the traffic and the rules ...", asker.model());
        // across services the app may make the ID itself, so the draft can name the field of its answer that carries it
        var schema = Schema.load(app.multiModule() ? "plan-draft-services" : "plan-draft");
        var answer = asker.ask(SYSTEM, prompt(app, map, aim, aim.deployment(), facts), schema, PlanDraft.class,
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
        // the fault goes where the risk is; code outside every module runs in the listener's
        String risky = app.code().method(risk.location()).map(Method::module).orElse(null);
        var journey = deployment.forJourney(null, listener.module(), risky == null ? listener.module() : risky);
        return new Aim(risk, listener, topic, FaultTemplate.of(risk, app, journey, listener, topic), journey);
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
        for (var r : d.rules()) {
            String sql = r.sql().strip().replaceAll(";+$", "").strip();
            String db = f.severalDatabases() ? databaseOfSql(f, sql).map(Db::name).orElse(null) : null;
            rules.add(new Plan.Rule(r.id(), sql, r.why(), true, db));
        }
        var t = test.traffic();
        var deployment = f.deployment();
        var shape = shapeOf(f, d.request().method(), d.request().path());
        String contentType = shape != null && shape.form() ? Plan.Request.FORM : null;
        // the closing newline makes plan.yaml show the body as a | block, so its quotes need no escaping
        var request = new Plan.Request(d.request().method(), d.request().path(), contentType, d.request().body().strip() + "\n");

        Plan.Database database = null;
        List<Plan.Database> databases = null;
        String effectDatabase = null;
        if (f.severalDatabases()) {
            effectDatabase = f.databaseOf(d.effect().table()).map(Db::name).orElse(null);
            databases = databases(f, reset, rules, effectDatabase);
        } else {
            database = new Plan.Database(f.dbUrl(), f.dbUser(), f.dbPassword(), reset, f.seedFile());
        }
        return new Plan(appName, d.journey(), aim.risk(),
            new Plan.Target(f.url(request), deployment.compose() == null ? f.jar() : null, deployment.compose(),
                args.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toList(), f.auth()),
            database, databases,
            new Plan.Kafka(f.bootstrap(), f.topic(), f.group()),
            test.relay(), null, test.stub() ? deployment.stub() : null,
            new Plan.Traffic(t.count(), t.rate(), null, request, t.drainTimeout(), t.warmup()),
            new Plan.CorrelateBy(d.correlateBy(), d.answerField(), effectDatabase, d.effect().table(), d.effect().column()),
            test.downstreamTopic() == null ? null : new Plan.Downstream(test.downstreamTopic(), d.correlateBy()),
            null,
            test.fault(), test.checks(), rules.isEmpty() ? null : rules, test.recoveryWithin(),
            CONFIRM);
    }

    /**
     * The databases a plan through several services uses, each with the reset statements on its tables: every database
     * the reset, the effects table or a rule names, in the order Faultline knows them. The seed file goes to the database
     * whose table it inserts into first.
     */
    private static List<Plan.Database> databases(Facts f, List<String> reset, List<Plan.Rule> rules, String effectDatabase) {
        var statements = new LinkedHashMap<String, List<String>>();
        for (String sql : reset) {
            resetTable(sql).flatMap(f::databaseOf).ifPresent(db -> statements.computeIfAbsent(db.name(), k -> new ArrayList<>()).add(sql));
        }
        String seedDatabase = null;
        if (f.seedFile() != null) {
            try {
                var first = INSERT.matcher(Files.readString(Path.of(f.seedFile()), StandardCharsets.UTF_8));
                if (first.find()) seedDatabase = f.databaseOf(first.group(1)).map(Db::name).orElse(null);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + f.seedFile(), e);
            }
        }
        var used = new HashSet<>(statements.keySet());
        if (effectDatabase != null) used.add(effectDatabase);
        if (seedDatabase != null) used.add(seedDatabase);
        rules.stream().map(Plan.Rule::database).filter(java.util.Objects::nonNull).forEach(used::add);
        var databases = new ArrayList<Plan.Database>();
        for (var db : f.databases()) {
            if (!used.contains(db.name())) continue;
            databases.add(new Plan.Database(db.name(), db.url(), db.user(), db.password(), statements.getOrDefault(db.name(), List.of()),
                db.name().equals(seedDatabase) ? f.seedFile() : null));
        }
        return databases;
    }

    /** The table a reset statement works on: TRUNCATE a, DELETE FROM a, UPDATE a SET. */
    static Optional<String> resetTable(String sql) {
        var m = RESET_TABLE.matcher(sql);
        return m.find() ? Optional.of(m.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    /** The one database every table a query reads is in; empty when they are in none, or in several. */
    static Optional<Db> databaseOfSql(Facts f, String sql) {
        var dbs = TABLE_REF.matcher(sql.toLowerCase(Locale.ROOT)).results().map(m -> f.databaseOf(m.group(1)))
            .flatMap(Optional::stream).map(Db::name).distinct().toList();
        return dbs.size() == 1 ? f.databases().stream().filter(d -> d.name().equals(dbs.getFirst())).findFirst() : Optional.empty();
    }

    /** The shape of an endpoint's request body, by its method and path. */
    private static RequestShape shapeOf(Facts f, String method, String path) {
        var shape = f.shapes().get(method + " " + path);
        return shape != null ? shape : f.shapes().get("ANY " + path);
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
        var shape = shapeOf(facts, d.request().method(), d.request().path());
        if (shape != null && d.request().body() != null) problems.addAll(bodyProblems(d.request().body(), shape));
        if (d.answerField() != null) {
            var answer = RequestShapes.answers(app).get(d.request().method() + " " + d.request().path());
            if (answer != null && !answer.contains(d.answerField())) {
                problems.add("responseField '%s' is not a field of the app's answer to %s %s (its fields: %s)".formatted(d.answerField(),
                    d.request().method(), d.request().path(), String.join(", ", answer)));
            }
        }

        var types = new ArrayList<>(journeyTypes(app, aim.listener()));
        // across services, the entry's own writes are the journey's too
        if (app.multiModule()) entry(app, map, d).ifPresent(h -> journeyTypes(app, h).stream().filter(t -> !types.contains(t)).forEach(types::add));
        Set<String> inserted = tables(app, types, INSERT);
        // a seeded table the journey saves through JPA is one it changes, not one it fills
        var saved = saved(app, types);
        var changed = new TreeSet<>(saved);
        changed.retainAll(seededTables(app));
        saved.removeAll(changed);
        inserted.addAll(saved);
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
        var updated = tables(app, types, UPDATE);
        updated.addAll(changed);
        for (String table : updated) {
            if (inserted.contains(table)) continue;
            var setBack = Pattern.compile("(?is)^\\s*update\\s+" + Pattern.quote(table) + "\\b");
            if (d.reset().stream().noneMatch(s -> setBack.matcher(s).find() || clears(table).matcher(s).find())) {
                problems.add("reset does not set " + table + " back, which the journey's code updates: UPDATE it to the values the migrations set");
            }
        }
        problems.addAll(ruleProblems(d.rules(), schema(app, facts.deployment())));
        if (facts.severalDatabases()) problems.addAll(databaseProblems(d, facts));
        problems.addAll(build(d, app.name(), aim, facts, settings).problems());
        return problems;
    }

    /** Across several databases, each table the draft names must be in exactly one of them, and each rule must stay in one. */
    static List<String> databaseProblems(PlanDraft d, Facts f) {
        var problems = new ArrayList<String>();
        String where = f.databases().stream().map(db -> db.name() + " (" + String.join(", ", db.schema().keySet()) + ")").collect(Collectors.joining("; "));
        if (f.databaseOf(d.effect().table()).isEmpty()) {
            problems.add("effect.table '%s' must be a table of exactly one of the app's databases: %s".formatted(d.effect().table(), where));
        }
        for (String sql : d.reset()) {
            var table = resetTable(sql);
            if (table.isEmpty() || f.databaseOf(table.get()).isEmpty()) {
                problems.add("reset '%s' must work on a table of exactly one of the app's databases: %s".formatted(sql, where));
            } else if (sql.contains(",")) {
                var tables = Arrays.stream(sql.replaceAll("(?is)^\\s*truncate(\\s+table)?", "").split("\\s*,\\s*"))
                    .map(s -> s.strip().split("\\s+")[0]).toList();
                if (tables.stream().map(f::databaseOf).flatMap(Optional::stream).map(Db::name).distinct().count() > 1) {
                    problems.add("reset '%s' names tables of different databases: one statement per database".formatted(sql));
                }
            }
        }
        for (var rule : d.rules()) {
            if (rule.sql() != null && databaseOfSql(f, rule.sql()).isEmpty()) {
                problems.add("rule %s must read the tables of one database: each service has its own (%s)".formatted(rule.id(), where));
            }
        }
        return problems;
    }

    /** The HTTP handler of the draft's request; between modules with the same path, one the journey goes through. */
    static Optional<Method> entry(App app, AppMap map, PlanDraft d) {
        var steps = map.journeys().stream().filter(j -> j.name().equals(d.journey())).flatMap(j -> j.steps().stream()).map(AppMap.Step::handler)
            .collect(Collectors.toSet());
        return httpEndpoints(app).stream()
            .filter(e -> e.path().equals(d.request().path()) && (e.method().equals(d.request().method()) || e.method().equals("ANY")))
            .sorted(Comparator.comparing(e -> !steps.contains(e.handler())))
            .flatMap(e -> app.code().method(e.handler()).stream()).findFirst();
    }

    /** Tables the methods of these types write through JPA repositories. */
    private static Set<String> saved(App app, List<TypeDeclaration<?>> types) {
        var tables = new TreeSet<String>();
        app.code().methods().stream().filter(m -> types.contains(m.owner())).forEach(m -> tables.addAll(JpaTables.saved(app.code(), m)));
        return tables;
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
     * The tables with their columns: as the running app made them when doctor has read them, else from the migrations'
     * create table and alter table ... add column (a table whose columns cannot be read has an empty set). The fake
     * provider's ledger is added when the deployment has it.
     */
    static Map<String, Set<String>> schema(App app, Deployment deployment) {
        var schema = new TreeMap<String, Set<String>>();
        var sql = new ArrayList<String>();
        if (app.environment() != null) schema.putAll(app.environment().schemaOf(App.ROOT_MODULE));
        else app.files().migrations().forEach(f -> sql.add(f.text()));
        if (deployment.stub() != null && !schema.containsKey("stub.charges")) sql.add(STUB_LEDGER);
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

    /**
     * Tables the app needs rows in from the start, such as accounts: those that had rows once the running app had started,
     * when doctor has read them, else those the migrations insert into.
     */
    static Set<String> seededTables(App app) {
        if (app.environment() != null) return app.environment().seededTablesOf(App.ROOT_MODULE);
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
            .line("The fault: Faultline will %s. The checks it already makes: %s.".formatted(test.does(), String.join(", ", made)));
        if (app.multiModule()) {
            prompt.section("The repo's Spring Boot apps, each a service with its own database", app.describeModules())
                .line("""
                    The journey crosses services. Each has its own database: name tables as they are, and Faultline runs each reset \
                    statement and each rule in the database that has its table, so a rule reads the tables of one database. A value \
                    that refers to a row of any service, such as an account ID, must be one the test data or a seeded table has. When \
                    the effect row holds an ID the app makes itself (a database sequence or a random UUID) rather than a field of the \
                    request, set responseField to the field of the app's JSON answer that carries it; else leave it empty.""");
        }
        prompt.code("App map", "json", Json.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new MapView(map.summary(), map.entryPoints(), map.journeys(), map.dataStores())))
            .section("HTTP endpoints that take a request body", http.stream().map(e -> "- " + e.name() + " -> " + e.handler()
                + (app.multiModule() ? app.code().method(e.handler()).flatMap(app::moduleOf).map(m -> " (" + m.name() + ")").orElse("") : ""))
                .collect(Collectors.joining("\n")))
            .section("Their request bodies", http.stream().filter(e -> facts.shapes().containsKey(e.name()))
                .map(e -> "### %s (from %s)\n\n%s".formatted(e.name(), facts.shapes().get(e.name()).source(), facts.shapes().get(e.name()).describe()))
                .collect(Collectors.joining("\n\n")));
        if (app.multiModule()) {
            var answers = RequestShapes.answers(app);
            prompt.section("The fields of their JSON answers", http.stream().filter(e -> answers.containsKey(e.name()))
                .map(e -> "- %s: %s".formatted(e.name(), String.join(", ", answers.get(e.name())))).collect(Collectors.joining("\n")));
        }
        if (http.stream().map(e -> facts.shapes().get(e.name())).anyMatch(s -> s != null && s.form())) {
            prompt.line("""
                An endpoint whose request body comes from form fields takes an HTML form. Write its body as JSON all the same, \
                with nested objects and lists as its field list shows them, such as {"address": {"street": "x"}, "lines": [{"count": 1}]}: \
                Faultline sends that as the form fields address.street=x and lines[0].count=1. A field read by one text value (such \
                as a customer by its ID) is a JSON string holding a value that exists. Name a request field of a nested object as \
                a.b, such as address.street.""");
        }
        prompt
            .code("Their handlers and request types", "java", handlerTypes.values().stream().map(app.code()::source).collect(Collectors.joining("\n\n")))
            .code("The listener " + aim.listener().handler() + ", the classes its work goes through, and the risky code", "java",
                journeyTypes.values().stream().map(app.code()::source).collect(Collectors.joining("\n\n")))
            .files("Database migrations", app.files().migrations(), "sql")
            .code(Environment.SCHEMA_TITLE, "sql", app.liveSchema());
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
        var config = app.configOf(aim.listener());
        String group = listener.groupId() != null ? listener.groupId() : config.get("spring.kafka.consumer.group-id")
            .orElseThrow(() -> new IllegalStateException("cannot tell the consumer group of " + aim.listener().handler()));
        if (app.multiModule()) return servicesFacts(app, deployment, aim, hints, config, group);
        String dbUrl = config.get("spring.datasource.url")
            .orElseThrow(() -> new IllegalStateException("the app's configuration has no spring.datasource.url"));
        String bootstrap = config.get("spring.kafka.bootstrap-servers").orElse("localhost:9092");
        // the app's services use in-network addresses; doctor knows where the same database and broker are from here
        if (app.environment() != null) {
            dbUrl = app.environment().hostJdbcUrl(dbUrl, App.ROOT_MODULE);
            bootstrap = app.environment().hostBootstrap(bootstrap);
        }
        return new Facts(deployment, deployment.compose() == null ? jar(app) : null, dbUrl,
            config.get("spring.datasource.username").orElse(""), config.get("spring.datasource.password").orElse(""),
            bootstrap, aim.topic(), group, RequestShapes.of(app), hints.auth(), hints.seedFile(app.dir()));
    }

    /**
     * The facts of a repo of several Spring Boot apps, from what doctor recorded: every database with its tables and its
     * address on this machine, and where each HTTP endpoint answers, on a service of its own module.
     */
    private static Facts servicesFacts(App app, Deployment deployment, Aim aim, Hints hints, SpringConfig listenerConfig, String group) {
        var env = app.environment();
        if (env == null) {
            throw new IllegalStateException(app.name() + " has " + app.modules().size() + " Spring Boot apps: run ./faultline doctor first, "
                + "so Faultline knows each one's database and how to reach it");
        }
        String bootstrap = env.hostBootstrap(listenerConfig.get("spring.kafka.bootstrap-servers").orElse("localhost:9092"));
        var names = env.databases().stream().collect(Collectors.groupingBy(Environment.Database::name, Collectors.counting()));
        var databases = new ArrayList<Db>();
        for (var d : env.databases()) {
            var schema = new TreeMap<String, Set<String>>();
            d.tables().forEach(t -> schema.put(t.name().toLowerCase(Locale.ROOT),
                t.columns().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new))));
            // a password Compose fills in from the shell is not recorded: the configuration of a module that uses it has it
            String password = d.password() != null ? d.password() : env.services().stream().filter(s -> d.usedBy().contains(s.name()))
                .flatMap(s -> app.module(s.module()).stream()).map(m -> m.config().get("spring.datasource.password").orElse(null))
                .filter(java.util.Objects::nonNull).findFirst().orElse("");
            String name = names.get(d.name()) > 1 ? d.service() + "-" + d.name() : d.name();
            databases.add(new Db(name, d.hostUrl(), d.user(), password, schema));
        }
        var urls = new LinkedHashMap<String, String>();
        for (var e : httpEndpoints(app)) {
            app.code().method(e.handler()).map(Method::module).flatMap(deployment::urlOf).ifPresent(url -> urls.put(e.name(), url));
        }
        return new Facts(deployment, null, null, null, null, bootstrap, aim.topic(), group, RequestShapes.of(app), hints.auth(),
            hints.seedFile(app.dir()), List.copyOf(databases), urls);
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
