package dev.faultline.cli.plan;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.workload.EffectsTable;
import dev.faultline.cli.workload.RequestTemplate;
import dev.faultline.cli.workload.Workload;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * plan.yaml: everything one run needs, in a file you can read, edit and approve. Which risk it tests, how to reach
 * the app, its database and Kafka, the traffic to send, how a request is matched to its effect, the fault, and the
 * checks, and how many runs it takes to confirm a bug. {@code risk}, {@code fault} and {@code confirm} are optional, so a
 * hand-written plan can be a plain fault-free run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Plan(String app, String journey, Risk risk, Target target, Database database, Kafka kafka,
                   Traffic traffic, CorrelateBy correlateBy, Fault fault, List<String> checks, Confirm confirm) {

    public static final List<String> DATA_CHECKS = List.of("no-lost", "no-duplicates", "no-unexpected");
    public static final String FAULTS_FIRED = "faults-fired";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final List<String> CODE_ACTIONS = List.of("crash", "delay", "throw");
    private static final List<String> PROCESS_ACTIONS = List.of("kill", "pause", "restart");
    private static final Set<String> CODE_WHEN = Set.of("before", "after");
    private static final Pattern RESET_STATEMENT = Pattern.compile("(?is)\\s*(truncate|delete\\s+from|update)\\s+[^;]+");

    // snake_case keys, few quotes, no folded lines, multi-line text (the request body) as a | block, nulls left out,
    // and unknown keys rejected: a typo in a hand edit should fail loudly, not be ignored
    private static final YAMLMapper YAML = YAMLMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
        .enable(YAMLWriteFeature.LITERAL_BLOCK_STYLE)
        .disable(YAMLWriteFeature.SPLIT_LINES)
        .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
        .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /**
     * The app under test, started by Faultline from a {@code jar} or as Docker {@code compose} services, or neither:
     * then it must already be running at {@code url}. {@code args} are Spring settings such as --payments.dedupe=none.
     */
    public record Target(String url, String jar, Compose compose, List<String> args) {}

    /**
     * Services of a Compose file, recreated for each run with its settings. Faultline waits until {@code url} answers and
     * the Kafka group has {@code consumers} members.
     */
    public record Compose(String file, String profile, List<String> services, Integer consumers) {}

    /** {@code reset} puts the database back to its starting state before each run. */
    public record Database(String url, String user, String password, List<String> reset) {}

    /** Where to read the consumer group's lag, to know when the app has finished. */
    public record Kafka(String bootstrap, String topic, String group) {}

    /** Without a {@code seed}, each run picks one and prints it. */
    public record Traffic(Integer count, Integer rate, Long seed, Request request) {}

    public record Request(String method, String path, String body) {}

    /** A request's {@code requestField} value appears in {@code table}.{@code column} once the app has processed it. */
    public record CorrelateBy(String requestField, String table, String column) {}

    /**
     * A fault in the code ({@code layer: code}, the default) fires from the agent: {@code action} crash, delay or throw,
     * {@code when} before or after the {@code point} method (or at before-ack), on every {@code nth_hit}th call until it has
     * fired {@code times} times. A {@code layer: process} fault is Docker's: kill, pause or restart the {@code target}
     * service {@code at} a time after the traffic starts, for {@code duration}. In Compose mode, {@code target} is also the
     * one service that gets the agent.
     */
    public record Fault(String layer, String point, String when, String action, Long nthHit, Integer times,
                        String duration, String target, String at) {
        public boolean inCode() {
            return layer == null || layer.equals("code");
        }

        public int count() {
            return times == null ? 1 : times;
        }
    }

    /**
     * A bug is confirmed when a data check fails in at least {@code minFailing} of {@code runs} runs with the fault firing, and
     * passes in every one of {@code baselines} runs without the fault.
     */
    public record Confirm(Integer runs, Integer baselines, Integer minFailing) {
        public static final Confirm SINGLE = new Confirm(1, 0, 1);
    }

    public static Plan read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("no plan at " + file);
        try {
            return YAML.readValue(file.toFile(), Plan.class);
        } catch (JacksonException e) {
            String where = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ")";
            throw new IllegalArgumentException("cannot read " + file + where + ": " + e.getOriginalMessage());
        }
    }

    public String toYaml() {
        return YAML.writeValueAsString(this);
    }

    /** Writes the plan with a few # comment lines on top. */
    public void write(Path file, List<String> header) throws IOException {
        var text = new StringBuilder();
        for (String line : header) text.append("# ").append(line).append('\n');
        text.append(toYaml());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /**
     * The plan as a run actually used it: a fixed seed, maybe other traffic numbers or confirm settings, and extra
     * app arguments, which win over the plan's own when they set the same key.
     */
    public Plan forRun(long seed, Integer count, Integer rate, List<String> extraArgs, Integer runs, Integer baselines) {
        var t = new Target(target.url(), target.jar(), target.compose(), withArgs(extraArgs));
        var tr = new Traffic(count != null ? count : traffic.count(), rate != null ? rate : traffic.rate(), seed, traffic.request());
        Confirm c = confirm();
        if (runs != null || baselines != null) {
            int r = runs != null ? runs : c.runs();
            c = new Confirm(r, baselines != null ? baselines : c.baselines(), Math.min(c.minFailing(), r));
        }
        return new Plan(app, journey, risk, t, database, kafka, tr, correlateBy, fault, checks, c);
    }

    /** The same plan without its fault: a baseline run, to show the checks pass when nothing is broken. */
    public Plan withoutFault() {
        var c = checks.stream().filter(check -> !check.equals(FAULTS_FIRED)).toList();
        return new Plan(app, journey, risk, target, database, kafka, traffic, correlateBy, null, c, confirm);
    }

    /** The plan's own confirm settings, or a single run without a baseline for plans that have none. */
    @Override
    public Confirm confirm() {
        return confirm != null ? confirm : Confirm.SINGLE;
    }

    /** target.args as settings: --payments.dedupe=none becomes payments.dedupe -> none, in order. */
    public Map<String, String> appSettings() {
        return settings(target.args());
    }

    static Map<String, String> settings(List<String> args) {
        var settings = new LinkedHashMap<String, String>();
        for (String arg : args == null ? List.<String>of() : args) {
            String s = arg.startsWith("--") ? arg.substring(2) : arg;
            int eq = s.indexOf('=');
            settings.put(eq < 0 ? s : s.substring(0, eq), eq < 0 ? "true" : s.substring(eq + 1));
        }
        return settings;
    }

    private List<String> withArgs(List<String> extraArgs) {
        var settings = appSettings();
        settings.putAll(settings(extraArgs));
        return settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toList();
    }

    public RequestTemplate template() {
        return RequestTemplate.parse(traffic.request().body());
    }

    public List<String> resetStatements() {
        return database.reset() == null ? List.of() : database.reset();
    }

    /** Everything wrong with the plan, in words a person editing it can act on. Empty if it can run. */
    public List<String> problems() {
        var problems = new ArrayList<String>();
        if (blank(app)) problems.add("app is missing");

        if (target == null || blank(target.url())) {
            problems.add("target.url is missing");
        } else if (!target.url().matches("https?://.+")) {
            problems.add("target.url must start with http:// or https://");
        } else {
            try {
                URI.create(target.url());
            } catch (IllegalArgumentException e) {
                problems.add("target.url is not a URL: " + e.getMessage());
            }
        }

        if (database == null || blank(database.url())) {
            problems.add("database.url is missing");
        } else {
            if (!database.url().startsWith("jdbc:postgresql:")) problems.add("database.url must be a jdbc:postgresql: URL (only Postgres for now)");
            if (database.user() == null) problems.add("database.user is missing");
            for (String sql : resetStatements()) {
                if (!RESET_STATEMENT.matcher(sql.strip().replaceAll(";$", "")).matches()) {
                    problems.add("database.reset: only single TRUNCATE, DELETE FROM and UPDATE statements are allowed, not: " + sql);
                }
            }
        }

        if (kafka == null || blank(kafka.bootstrap()) || blank(kafka.topic()) || blank(kafka.group())) {
            problems.add("kafka needs bootstrap, topic and group");
        }

        if (correlateBy == null || blank(correlateBy.requestField())) {
            problems.add("correlate_by.request_field is missing");
        } else {
            if (!EffectsTable.isIdentifier(correlateBy.table())) problems.add("correlate_by.table is not a table name: " + correlateBy.table());
            if (!EffectsTable.isIdentifier(correlateBy.column())) problems.add("correlate_by.column is not a column name: " + correlateBy.column());
        }

        if (traffic == null || traffic.request() == null) {
            problems.add("traffic.request is missing");
        } else {
            if (traffic.count() == null || traffic.count() < 1) problems.add("traffic.count must be at least 1");
            if (traffic.rate() == null || traffic.rate() < 1) problems.add("traffic.rate must be at least 1");
            var r = traffic.request();
            if (r.method() == null || !BODY_METHODS.contains(r.method())) problems.add("traffic.request.method must be POST, PUT or PATCH");
            if (r.path() == null || !r.path().startsWith("/")) problems.add("traffic.request.path must start with /");
            if (correlateBy != null && !blank(correlateBy.requestField())) problems.addAll(bodyProblems(r.body(), correlateBy.requestField()));
        }

        if (target != null) {
            if (!blank(target.jar()) && target.compose() != null) problems.add("target needs a jar or compose, not both");
            var c = target.compose();
            if (c != null) {
                if (blank(c.file())) problems.add("target.compose.file is missing");
                if (c.services() == null || c.services().isEmpty()) problems.add("target.compose.services is empty");
                if (c.consumers() != null && c.consumers() < 0) problems.add("target.compose.consumers cannot be negative");
            }
        }

        if (fault != null) problems.addAll(faultProblems());

        var c = confirm();
        if (c.runs() == null || c.runs() < 1) problems.add("confirm.runs must be at least 1");
        if (c.baselines() == null || c.baselines() < 0) problems.add("confirm.baselines cannot be negative");
        if (c.minFailing() == null || c.minFailing() < 1 || c.runs() != null && c.minFailing() > c.runs()) {
            problems.add("confirm.min_failing must be between 1 and confirm.runs");
        }

        if (checks == null || checks.isEmpty()) {
            problems.add("checks is empty");
        } else {
            for (String check : checks) {
                if (!DATA_CHECKS.contains(check) && !check.equals(FAULTS_FIRED)) {
                    problems.add("unknown check '" + check + "': use " + String.join(", ", DATA_CHECKS) + " or " + FAULTS_FIRED);
                }
            }
            if (checks.contains(FAULTS_FIRED) && fault == null) problems.add("check faults-fired needs a fault");
        }
        return problems;
    }

    private List<String> faultProblems() {
        var problems = new ArrayList<String>();
        boolean startsApp = target != null && (!blank(target.jar()) || target.compose() != null);
        var services = target != null && target.compose() != null && target.compose().services() != null ? target.compose().services() : List.<String>of();
        if (fault.times() != null && fault.times() < 1) problems.add("fault.times must be at least 1");

        if (fault.inCode()) {
            if (blank(fault.point()) || !fault.point().equals("before-ack") && !fault.point().matches("[\\w.$]+#\\w+")) {
                problems.add("fault.point must be before-ack or Class#method, such as PaymentService#apply");
            }
            if (fault.when() != null && !CODE_WHEN.contains(fault.when())) problems.add("fault.when must be before or after");
            if (!CODE_ACTIONS.contains(fault.action())) problems.add("fault.action must be one of " + CODE_ACTIONS + " for a code fault");
            if (fault.nthHit() == null || fault.nthHit() < 1) problems.add("fault.nth_hit must be at least 1");
            if ("delay".equals(fault.action()) && !Durations.valid(fault.duration())) problems.add("a delay needs a duration, such as 15s");
            if (!startsApp) problems.add("a code fault needs target.jar or target.compose: Faultline has to start the app to attach its agent");
            if (!services.isEmpty() && !services.contains(fault.target())) {
                problems.add("fault.target must name the service that gets the agent, one of " + services);
            }
        } else if (fault.layer().equals("process")) {
            if (!PROCESS_ACTIONS.contains(fault.action())) problems.add("fault.action must be one of " + PROCESS_ACTIONS + " for a process fault");
            if (services.isEmpty()) problems.add("a process fault needs target.compose: it stops and starts Docker services");
            else if (!services.contains(fault.target())) problems.add("fault.target must be one of " + services);
            if (!Durations.valid(fault.at())) problems.add("fault.at must say when, after the traffic starts, such as 10s");
            if (!"restart".equals(fault.action()) && !Durations.valid(fault.duration())) {
                problems.add("fault.duration must say how long the service stays down, such as 15s");
            }
        } else {
            problems.add("fault.layer must be code or process");
        }
        return problems;
    }

    /** The template must parse, give JSON with the ID field, and give each request its own ID. */
    private static List<String> bodyProblems(String body, String idField) {
        try {
            var template = RequestTemplate.parse(body);
            var random = new Random(1);
            String first = Workload.idOf(template.render(1, 0, random), idField);
            String second = Workload.idOf(template.render(1, 1, random), idField);
            String otherSeed = Workload.idOf(template.render(2, 0, new Random(2)), idField);
            if (first.equals(second)) return List.of("traffic.request.body: '" + idField + "' must differ per request: build it from ${seq}");
            if (first.equals(otherSeed)) return List.of("traffic.request.body: '" + idField + "' must differ per run: build it from ${seed}");
            return List.of();
        } catch (IllegalArgumentException e) {
            return List.of("traffic.request.body: " + e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
