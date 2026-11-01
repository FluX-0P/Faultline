package dev.faultline.cli.plan;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.faultline.cli.Json;
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
 * hand-written plan can be a plain fault-free run. {@code toxiproxy} and {@code stub} are the addresses Faultline drives
 * network faults and the fake payment provider through. An app with one database has it under {@code database}; one
 * whose journey goes through several services has each of their databases, by name, under {@code databases}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Plan(String app, String journey, Risk risk, Target target, Database database, List<Database> databases, Kafka kafka,
                   Relay relay, String toxiproxy, String stub, Traffic traffic, CorrelateBy correlateBy, Downstream downstream, Order order,
                   Fault fault, List<String> checks, List<Rule> rules, String recoveryWithin, Confirm confirm) {

    /** A plan with one database. */
    public Plan(String app, String journey, Risk risk, Target target, Database database, Kafka kafka, Relay relay,
                String toxiproxy, String stub, Traffic traffic, CorrelateBy correlateBy, Downstream downstream, Order order,
                Fault fault, List<String> checks, List<Rule> rules, String recoveryWithin, Confirm confirm) {
        this(app, journey, risk, target, database, null, kafka, relay, toxiproxy, stub, traffic, correlateBy, downstream, order, fault,
            checks, rules, recoveryWithin, confirm);
    }

    public static final List<String> DATA_CHECKS = List.of("no-lost", "no-duplicates", "no-unexpected");
    public static final String FAULTS_FIRED = "faults-fired";
    public static final String PER_KEY_ORDER = "per-key-order";
    public static final String NO_LOST_DOWNSTREAM = "no-lost-downstream";
    public static final String RECOVERY_WITHIN = "recovery-within";
    private static final List<String> OTHER_CHECKS = List.of(PER_KEY_ORDER, NO_LOST_DOWNSTREAM, RECOVERY_WITHIN, FAULTS_FIRED);
    private static final List<String> TRAFFIC_ACTIONS = List.of("duplicate");
    private static final List<String> MESSAGING_ACTIONS = List.of("duplicate", "reorder", "corrupt");
    private static final List<String> NETWORK_ACTIONS = List.of("latency", "timeout", "cut");
    private static final List<String> SERVICE_ACTIONS = List.of("late-reply", "error", "slow", "outage");
    private static final Pattern RULE_SQL = Pattern.compile("(?is)\\s*(select|with)\\s[^;]+;?\\s*");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final List<String> CODE_ACTIONS = List.of("crash", "delay", "throw", "barrier");
    private static final List<String> PROCESS_ACTIONS = List.of("kill", "pause", "restart");
    private static final String CLASS_AND_METHOD = "[\\w.$]+#\\w+";
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
     * {@code auth} (may be null) goes on every request.
     */
    public record Target(String url, String jar, Compose compose, List<String> args, Auth auth) {
        public Target(String url, String jar, Compose compose, List<String> args) {
            this(url, jar, compose, args, null);
        }
    }

    /**
     * A header every request carries, such as Authorization: Bearer ${env:SHOP_TOKEN}. {@code ${env:NAME}} is the
     * environment variable (or .env entry) NAME, read when the run starts, so the secret itself is never in a plan.
     */
    public record Auth(String header, String value) {
        public static final Pattern ENV = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");
    }

    /**
     * Services of a Compose file, recreated for each run with its settings. Faultline waits until {@code url} answers and
     * the Kafka group has {@code consumers} members. {@code override} (may be null) is Faultline's own Compose file from
     * faultline doctor, read after the app's: published database ports and a Kafka listener for this machine.
     * {@code connect} (may be null) is the REST API of a Kafka Connect service among them, such as Debezium carrying an
     * outbox to Kafka: Faultline also waits until each of its connectors runs. {@code flush} names Redis services the app
     * keeps state in, such as the answers it gave per idempotency key: each reset empties them too, or a run would get the
     * last run's answers.
     */
    public record Compose(String file, String profile, List<String> services, Integer consumers, String override, String connect,
                          List<String> flush) {
        public Compose(String file, String profile, List<String> services, Integer consumers) {
            this(file, profile, services, consumers, null, null, null);
        }

        public Compose(String file, String profile, List<String> services, Integer consumers, String override) {
            this(file, profile, services, consumers, override, null, null);
        }

        /** The Redis services Faultline empties at every reset; empty when there are none. */
        public List<String> flushed() {
            return flush == null ? List.of() : flush;
        }

        /** The Compose files, in the order Compose reads them: the app's, then Faultline's override. */
        public List<String> files() {
            var files = new ArrayList<>(appFiles(file));
            if (override != null) files.add(override);
            return files;
        }

        /** An app's Compose files, from {@code file}: one, or several separated by commas, read in that order. */
        public static List<String> appFiles(String file) {
            return Plan.files(file);
        }
    }

    /**
     * {@code reset} puts the database back to its starting state before each run. {@code seedFile} (may be null) is SQL
     * that loads test data after every reset, once the app has created its tables; it must be able to run again, such as
     * insert ... on conflict do nothing. {@code name} tells the databases of a plan with several apart; the one database
     * of a plan has none.
     */
    public record Database(String name, String url, String user, String password, List<String> reset, String seedFile) {
        public Database(String url, String user, String password, List<String> reset, String seedFile) {
            this(null, url, user, password, reset, seedFile);
        }

        public Database(String url, String user, String password, List<String> reset) {
            this(null, url, user, password, reset, null);
        }

        public List<String> resetStatements() {
            return reset == null ? List.of() : reset;
        }
    }

    /** Where to read the consumer group's lag, to know when the app has finished. */
    public record Kafka(String bootstrap, String topic, String group) {}

    /**
     * Without a {@code seed}, each run picks one. {@code drainTimeout} caps the wait for the app to finish (default 3m).
     * {@code warmup} requests (other IDs, same rate) go first, so a JVM that has just started is not slow on the measured
     * ones; Faultline waits until the app has finished them and resets the database again before the run starts.
     */
    public record Traffic(Integer count, Integer rate, Long seed, Request request, String drainTimeout, Integer warmup) {
        public Traffic(Integer count, Integer rate, Long seed, Request request, String drainTimeout) {
            this(count, rate, seed, request, drainTimeout, null);
        }

        public int warmupCount() {
            return warmup == null ? 0 : warmup;
        }
    }

    /**
     * A chaos relay Faultline runs between two topics: the app publishes to {@code from}, the relay forwards to {@code to}
     * and fires messaging faults on the way. The app must be set to publish to {@code from}: by a setting of its own, or,
     * when the code names the topic, by the agent in each of {@code senders}, the Compose services whose sends to
     * {@code to} it renames to {@code from}.
     */
    public record Relay(String from, String to, List<String> senders) {
        public Relay(String from, String to) {
            this(from, to, null);
        }

        /** The services whose agent renames the topic; empty when a setting moves the app's sends. */
        public List<String> senderServices() {
            return senders == null ? List.of() : senders;
        }

        /** The agent's rename, to=>from, for the senders' agents; null without senders. */
        public String rename() {
            return senderServices().isEmpty() ? null : to + ">" + from;
        }
    }

    /**
     * A topic the app publishes to once it has done its work, such as payments.completed. Each event names its request
     * in {@code requestField}; no-lost-downstream checks that every applied request has one.
     */
    public record Downstream(String topic, String requestField) {}

    /**
     * For per-key-order: requests with the same {@code key} field must be applied in the order they happened, read from
     * the effects table's {@code by} column (one that grows as rows are added, such as an id).
     */
    public record Order(String key, String by) {}

    /**
     * An app rule: SQL that must return no rows once the run is over. Each row it returns is an offender. A {@code drafted}
     * rule is the LLM's: it counts only once a baseline run without the fault has passed it, and one that fails there is
     * dropped, since the rule is wrong, not the app. Take {@code drafted} out to vouch for a rule yourself. {@code database}
     * names the database it runs in, in a plan with several (null: the first).
     */
    public record Rule(String id, String sql, String why, Boolean drafted, String database) {
        public Rule(String id, String sql, String why, Boolean drafted) {
            this(id, sql, why, drafted, null);
        }

        public Rule(String id, String sql) {
            this(id, sql, null, null, null);
        }

        // not isDrafted(): Jackson would take that for the drafted property and write drafted: false on every rule
        public boolean byLlm() {
            return Boolean.TRUE.equals(drafted);
        }
    }

    /**
     * The request that starts the journey. {@code body} is a JSON template; with {@code contentType}
     * application/x-www-form-urlencoded Faultline sends it as form fields instead, nested as a.b and a[0] (null: JSON).
     */
    public record Request(String method, String path, String contentType, String body) {
        public static final String FORM = "application/x-www-form-urlencoded";

        public Request(String method, String path, String body) {
            this(method, path, null, body);
        }

        public boolean form() {
            return FORM.equals(contentType);
        }
    }

    /**
     * A request's {@code requestField} value appears in {@code table}.{@code column} once the app has processed it. When
     * the app makes the ID itself, {@code responseField} names where its JSON answer to the request carries it, and the
     * column holds that ID instead. {@code database} names the table's database in a plan with several (null: the first).
     */
    public record CorrelateBy(String requestField, String responseField, String database, String table, String column) {
        public CorrelateBy(String requestField, String table, String column) {
            this(requestField, null, null, table, column);
        }
    }

    /**
     * A fault in the code ({@code layer: code}, the default) fires from the agent: {@code action} crash, delay, throw or
     * barrier, {@code when} before or after the {@code point} method (or at before-ack), on every {@code nth_hit}th call until
     * it has fired {@code times} times. A barrier holds the call for up to {@code duration}, until one of the {@code partners}
     * (other services) or another thread returns from {@code until}. In Compose mode, {@code target} gets the agent.
     *
     * <p>The other layers: {@code process} kills, pauses or restarts the {@code target} service; {@code network} slows
     * ({@code latency}), stalls ({@code timeout}) or cuts the connections of the {@code target} Toxiproxy proxy;
     * {@code service} makes the stub answer every nth charge late ({@code late-reply}: after charging it), slowly or with an
     * error, or go through an {@code outage}. Those that last a while start {@code at} a time after the traffic starts and
     * last {@code duration}. {@code traffic} and {@code messaging} duplicate (or reorder or corrupt) every nth request or
     * relayed message.
     */
    public record Fault(String layer, String point, String when, String action, Long nthHit, Integer times,
                        String duration, String target, String at, String until, List<String> partners, String latency) {
        public Fault(String layer, String point, String when, String action, Long nthHit, Integer times, String duration,
                     String target, String at) {
            this(layer, point, when, action, nthHit, times, duration, target, at, null, null, null);
        }

        public boolean inCode() {
            return layer == null || layer.equals("code");
        }

        public int count() {
            return times == null ? 1 : times;
        }

        /** Whether it lasts from {@code at} for {@code duration}, rather than firing on every nth call. */
        public boolean timed() {
            return "process".equals(layer) || "network".equals(layer) || "service".equals(layer) && "outage".equals(action);
        }

        public List<String> partnerServices() {
            return partners == null ? List.of() : partners;
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
        var t = new Target(target.url(), target.jar(), target.compose(), withArgs(extraArgs), target.auth());
        var tr = new Traffic(count != null ? count : traffic.count(), rate != null ? rate : traffic.rate(), seed, traffic.request(),
            traffic.drainTimeout(), traffic.warmup());
        Confirm c = confirm();
        if (runs != null || baselines != null) {
            int r = runs != null ? runs : c.runs();
            c = new Confirm(r, baselines != null ? baselines : c.baselines(), Math.min(c.minFailing(), r));
        }
        return new Plan(app, journey, risk, t, database, databases, kafka, relay, toxiproxy, stub, tr, correlateBy, downstream, order, fault,
            checks, rules, recoveryWithin, c);
    }

    /**
     * The same plan without its fault: a baseline run, to show the checks pass when nothing is broken. With no fault there
     * is nothing to recover from, so recovery-within goes too.
     */
    public Plan withoutFault() {
        var c = checks.stream().filter(check -> !check.equals(FAULTS_FIRED) && !check.equals(RECOVERY_WITHIN)).toList();
        return new Plan(app, journey, risk, target, database, databases, kafka, relay, toxiproxy, stub, traffic, correlateBy, downstream, order,
            null, c, rules, recoveryWithin, confirm);
    }

    /** The plan's databases: those under {@code databases}, or the one under {@code database}. */
    public List<Database> allDatabases() {
        if (databases != null) return databases;
        return database == null ? List.of() : List.of(database);
    }

    /** The database a part of the plan names; null is the first, the only one in a plan with one database. */
    public Database databaseNamed(String name) {
        var all = allDatabases();
        if (name == null) return all.isEmpty() ? null : all.getFirst();
        return all.stream().filter(d -> name.equals(d.name())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no database named " + name + " in the plan"));
    }

    /** The database the effects table is in. */
    public Database effectsDatabase() {
        return databaseNamed(correlateBy == null ? null : correlateBy.database());
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

    /** The reset statements of the plan's first database. */
    public List<String> resetStatements() {
        var db = databaseNamed(null);
        return db == null ? List.of() : db.resetStatements();
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

        if (database != null && databases != null) {
            problems.add("use database (one) or databases (several), not both");
        } else if (databases != null) {
            if (databases.isEmpty()) problems.add("databases is empty");
            var names = new LinkedHashSet<String>();
            for (var db : databases) {
                if (blank(db.name()) || !names.add(db.name())) problems.add("every database of databases needs its own name");
                problems.addAll(databaseProblems(db, "databases." + db.name()));
            }
            if (correlateBy != null && correlateBy.database() != null && !names.contains(correlateBy.database())) {
                problems.add("correlate_by.database must name one of databases: " + String.join(", ", names));
            }
            for (Rule rule : rules == null ? List.<Rule>of() : rules) {
                if (rule.database() != null && !names.contains(rule.database())) {
                    problems.add("rule " + rule.id() + ": database must name one of databases: " + String.join(", ", names));
                }
            }
        } else if (database == null || blank(database.url())) {
            problems.add("database.url is missing");
        } else {
            problems.addAll(databaseProblems(database, "database"));
            if (correlateBy != null && correlateBy.database() != null) problems.add("correlate_by.database is only for a plan with databases");
        }

        if (kafka == null || blank(kafka.bootstrap()) || blank(kafka.topic()) || blank(kafka.group())) {
            problems.add("kafka needs bootstrap, topic and group");
        }

        if (correlateBy == null || blank(correlateBy.requestField())) {
            problems.add("correlate_by.request_field is missing");
        } else {
            if (!EffectsTable.isIdentifier(correlateBy.table())) problems.add("correlate_by.table is not a table name: " + correlateBy.table());
            if (!EffectsTable.isIdentifier(correlateBy.column())) problems.add("correlate_by.column is not a column name: " + correlateBy.column());
            if (correlateBy.responseField() != null && blank(correlateBy.responseField())) problems.add("correlate_by.response_field is empty");
        }

        if (traffic == null || traffic.request() == null) {
            problems.add("traffic.request is missing");
        } else {
            if (traffic.count() == null || traffic.count() < 1) problems.add("traffic.count must be at least 1");
            if (traffic.warmup() != null && traffic.warmup() < 0) problems.add("traffic.warmup cannot be negative");
            if (traffic.rate() == null || traffic.rate() < 1) problems.add("traffic.rate must be at least 1");
            var r = traffic.request();
            if (r.method() == null || !BODY_METHODS.contains(r.method())) problems.add("traffic.request.method must be POST, PUT or PATCH");
            if (r.path() == null || !r.path().startsWith("/")) problems.add("traffic.request.path must start with /");
            if (r.contentType() != null && !r.form() && !r.contentType().equals("application/json")) {
                problems.add("traffic.request.content_type must be application/json or " + Request.FORM);
            }
            if (correlateBy != null && !blank(correlateBy.requestField())) problems.addAll(bodyProblems(r.body(), correlateBy.requestField()));
            if (r.path() != null) problems.addAll(pathProblems(r.path(), r.body()));
        }

        if (target != null) {
            if (!blank(target.jar()) && target.compose() != null) problems.add("target needs a jar or compose, not both");
            var auth = target.auth();
            if (auth != null) {
                if (blank(auth.header()) || !auth.header().matches("[A-Za-z0-9-]+")) problems.add("target.auth.header must be a header name, such as Authorization");
                if (blank(auth.value())) {
                    problems.add("target.auth.value is missing");
                } else if (auth.value().replaceAll(Auth.ENV.pattern(), "").contains("${")) {
                    problems.add("target.auth.value: the only placeholder is ${env:NAME}, an environment variable that holds the secret");
                }
            }
            var c = target.compose();
            if (c != null) {
                if (blank(c.file())) problems.add("target.compose.file is missing");
                if (c.override() != null && !Files.isRegularFile(Path.of(c.override()))) {
                    problems.add("target.compose.override: no file at " + c.override() + " (run ./faultline doctor again)");
                }
                if (c.services() == null || c.services().isEmpty()) problems.add("target.compose.services is empty");
                if (c.consumers() != null && c.consumers() < 0) problems.add("target.compose.consumers cannot be negative");
                if (c.connect() != null && !c.connect().matches("https?://[^/]+/?")) {
                    problems.add("target.compose.connect must be the address of Kafka Connect's REST API, such as http://localhost:8083");
                }
            }
        }

        if (fault != null) problems.addAll(faultProblems());

        if (relay != null && (blank(relay.from()) || blank(relay.to()) || relay.from().equals(relay.to()))) {
            problems.add("relay needs two different topics, from and to");
        }
        if (relay != null && !relay.senderServices().isEmpty()) {
            var services = target != null && target.compose() != null && target.compose().services() != null ? target.compose().services() : List.<String>of();
            if (services.isEmpty()) problems.add("relay.senders needs target.compose: they are its services that get the agent");
            for (String s : relay.senderServices()) {
                if (!services.isEmpty() && !services.contains(s)) problems.add("relay.senders must be services of target.compose.services, not " + s);
            }
        }
        if (order != null && (blank(order.key()) || !EffectsTable.isIdentifier(order.by()))) {
            problems.add("order needs key (a request field) and by (a column of " + (correlateBy == null ? "the effects table" : correlateBy.table()) + ")");
        }
        if (traffic != null && traffic.drainTimeout() != null && !Durations.valid(traffic.drainTimeout())) {
            problems.add("traffic.drain_timeout must be a duration, such as 60s");
        }
        if (downstream != null && (blank(downstream.topic()) || blank(downstream.requestField()))) {
            problems.add("downstream needs topic and request_field (the field of each event that holds the request's ID)");
        }
        if (recoveryWithin != null && !Durations.valid(recoveryWithin)) problems.add("recovery_within must be a duration, such as 30s");
        for (var url : new String[] {toxiproxy, stub}) {
            if (url != null && !url.matches("https?://[^/]+/?")) problems.add("toxiproxy and stub must be addresses such as http://localhost:8474, not " + url);
        }
        var ruleIds = new HashSet<String>();
        for (Rule rule : rules == null ? List.<Rule>of() : rules) {
            if (blank(rule.id()) || !ruleIds.add(rule.id())) problems.add("every rule needs its own id");
            if (rule.sql() == null || !RULE_SQL.matcher(rule.sql()).matches()) {
                problems.add("rule " + rule.id() + ": sql must be one SELECT (or WITH) statement that returns the offending rows");
            }
        }

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
                if (!DATA_CHECKS.contains(check) && !OTHER_CHECKS.contains(check)) {
                    problems.add("unknown check '" + check + "': use " + String.join(", ", DATA_CHECKS) + ", "
                        + String.join(", ", OTHER_CHECKS.subList(0, OTHER_CHECKS.size() - 1)) + " or " + OTHER_CHECKS.getLast());
                }
            }
            if (checks.contains(FAULTS_FIRED) && fault == null) problems.add("check faults-fired needs a fault");
            if (checks.contains(PER_KEY_ORDER) && order == null) problems.add("check per-key-order needs order: key and by");
            if (checks.contains(NO_LOST_DOWNSTREAM) && downstream == null) problems.add("check no-lost-downstream needs downstream: topic and request_field");
            if (checks.contains(RECOVERY_WITHIN) && (recoveryWithin == null || fault == null || !fault.timed())) {
                problems.add("check recovery-within needs recovery_within (such as 30s) and a fault that lasts from at for a duration (process, network or a service outage)");
            }
        }
        return problems;
    }

    private List<String> faultProblems() {
        var problems = new ArrayList<String>();
        boolean startsApp = target != null && (!blank(target.jar()) || target.compose() != null);
        var services = target != null && target.compose() != null && target.compose().services() != null ? target.compose().services() : List.<String>of();
        if (fault.times() != null && fault.times() < 1) problems.add("fault.times must be at least 1");

        if (fault.inCode()) {
            if (blank(fault.point()) || !fault.point().equals("before-ack") && !fault.point().matches(CLASS_AND_METHOD)) {
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
            if ("barrier".equals(fault.action())) {
                if (fault.until() == null || !fault.until().matches(CLASS_AND_METHOD)) {
                    problems.add("a barrier needs until: the Class#method another worker must return from before it lets go");
                }
                if (!Durations.valid(fault.duration())) problems.add("a barrier needs a duration: the longest it holds, such as 3s");
                for (String partner : fault.partnerServices()) {
                    if (!services.contains(partner) || partner.equals(fault.target())) {
                        problems.add("fault.partners must be other services of target.compose.services, not " + partner);
                    }
                }
            } else if (fault.until() != null || fault.partners() != null) {
                problems.add("fault.until and fault.partners are only for a barrier");
            }
        } else if (fault.layer().equals("network")) {
            if (!NETWORK_ACTIONS.contains(fault.action())) problems.add("fault.action must be one of " + NETWORK_ACTIONS + " for a network fault");
            if (toxiproxy == null) problems.add("a network fault needs toxiproxy: the address of its API, such as http://localhost:8474");
            if (blank(fault.target())) problems.add("fault.target must name the Toxiproxy proxy to break, such as worker-1-db");
            if ("latency".equals(fault.action()) && !Durations.valid(fault.latency())) problems.add("a latency fault needs latency, such as 3s");
            problems.addAll(timeProblems());
        } else if (fault.layer().equals("service")) {
            if (!SERVICE_ACTIONS.contains(fault.action())) problems.add("fault.action must be one of " + SERVICE_ACTIONS + " for a service fault");
            if (stub == null) problems.add("a service fault needs stub: the address of the fake payment provider, such as http://localhost:9090");
            if ("outage".equals(fault.action())) {
                problems.addAll(timeProblems());
            } else {
                if (fault.nthHit() == null || fault.nthHit() < 1) problems.add("fault.nth_hit must be at least 1");
                if (!"error".equals(fault.action()) && !Durations.valid(fault.duration())) {
                    problems.add("fault.duration must say how late the stub replies, such as 3s");
                }
            }
        } else if (fault.layer().equals("process")) {
            if (!PROCESS_ACTIONS.contains(fault.action())) problems.add("fault.action must be one of " + PROCESS_ACTIONS + " for a process fault");
            if (services.isEmpty()) problems.add("a process fault needs target.compose: it stops and starts Docker services");
            else if (!services.contains(fault.target())) problems.add("fault.target must be one of " + services);
            if (!Durations.valid(fault.at())) problems.add("fault.at must say when, after the traffic starts, such as 10s");
            if (!"restart".equals(fault.action()) && !Durations.valid(fault.duration())) {
                problems.add("fault.duration must say how long the service stays down, such as 15s");
            }
        } else if (fault.layer().equals("traffic")) {
            if (!TRAFFIC_ACTIONS.contains(fault.action())) problems.add("fault.action must be one of " + TRAFFIC_ACTIONS + " for a traffic fault");
            if (fault.nthHit() == null || fault.nthHit() < 1) problems.add("fault.nth_hit must be at least 1");
        } else if (fault.layer().equals("messaging")) {
            if (!MESSAGING_ACTIONS.contains(fault.action())) problems.add("fault.action must be one of " + MESSAGING_ACTIONS + " for a messaging fault");
            if (fault.nthHit() == null || fault.nthHit() < 1) problems.add("fault.nth_hit must be at least 1");
            if (relay == null) problems.add("a messaging fault needs a relay: from and to topics");
        } else {
            problems.add("fault.layer must be code, process, network, service, traffic or messaging");
        }
        return problems;
    }

    /** @param where how the plan names it, such as database or databases.shipping */
    private static List<String> databaseProblems(Database db, String where) {
        var problems = new ArrayList<String>();
        if (blank(db.url())) {
            problems.add(where + ".url is missing");
            return problems;
        }
        if (!db.url().startsWith("jdbc:postgresql:")) problems.add(where + ".url must be a jdbc:postgresql: URL (only Postgres for now)");
        if (db.user() == null) problems.add(where + ".user is missing");
        if (db.seedFile() != null && !db.seedFile().endsWith(".sql")) problems.add(where + ".seed_file must be a .sql file");
        for (String sql : db.resetStatements()) {
            if (!RESET_STATEMENT.matcher(sql.strip().replaceAll(";$", "")).matches()) {
                problems.add(where + ".reset: only single TRUNCATE, DELETE FROM and UPDATE statements are allowed, not: " + sql);
            }
        }
        return problems;
    }

    private List<String> timeProblems() {
        var problems = new ArrayList<String>();
        if (!Durations.valid(fault.at())) problems.add("fault.at must say when, after the traffic starts, such as 10s");
        if (!Durations.valid(fault.duration())) problems.add("fault.duration must say how long it lasts, such as 15s");
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

    /** Files named as one, or as several separated by commas, such as the seed files of several databases; none for null. */
    public static List<String> files(String names) {
        return names == null ? List.of() : Arrays.stream(names.split(",")).map(String::strip).filter(f -> !f.isEmpty()).toList();
    }

    /** Each {name} in the path is filled from the body's field of that name, so the body must have one. */
    private static List<String> pathProblems(String path, String body) {
        var names = Workload.pathVariables(path);
        if (names.isEmpty()) return List.of();
        try {
            var json = Json.MAPPER.readTree(RequestTemplate.parse(body).render(1, 0, new Random(1)));
            return names.stream().filter(n -> Workload.field(json, n).isEmpty())
                .map(n -> "traffic.request.path has {%s}, so the body needs a field %s: Faultline fills the path from it".formatted(n, n)).toList();
        } catch (IllegalArgumentException | JacksonException e) {
            return List.of();   // a body that does not parse is reported on its own
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
