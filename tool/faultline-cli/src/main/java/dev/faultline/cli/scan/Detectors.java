package dev.faultline.cli.scan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.Endpoint;
import dev.faultline.cli.source.JavaSources;
import dev.faultline.cli.source.JavaSources.Method;
import dev.faultline.cli.source.SpringConfig;
import dev.faultline.cli.source.SqlTables;

/**
 * Cheap static checks that find candidates for each pattern. They aim to miss nothing and may flag code that is
 * safe: the LLM judges every candidate afterwards. Each candidate says what the check saw, for the LLM and the report.
 */
final class Detectors {
    /** A place in the code that may have a pattern's risk. */
    /** {@code caller}: the listener that leads to it, when that matters (P3), else null. */
    record Candidate(String pattern, Method method, String saw, Method caller) {
        Candidate(String pattern, Method method, String saw) {
            this(pattern, method, saw, null);
        }
    }

    private static final Set<String> HAND_OFF = Set.of("submit", "execute", "runAsync", "supplyAsync");
    private static final Set<String> HTTP_CLIENTS = Set.of("RestClient", "RestTemplate", "WebClient", "HttpClient");
    private static final Set<String> HTTP_CALLS = Set.of("retrieve", "exchange", "postForObject", "postForEntity", "getForObject", "getForEntity");
    private static final Set<String> RETRY_TYPES = Set.of("FixedBackOff", "ExponentialBackOff", "ExponentialBackOffWithMaxRetries", "RetryTemplate");
    private static final Set<String> ERROR_HANDLERS = Set.of("DefaultErrorHandler", "SeekToCurrentErrorHandler", "CommonLoggingErrorHandler");

    /** A Kafka Connect image in a Compose file, such as debezium/connect or confluentinc/cp-kafka-connect. */
    private static final Pattern CONNECT_IMAGE = Pattern.compile("(?m)image:\\s*\\S*(debezium/connect|cp-kafka-connect|kafka-connect|/connect:)");

    private final JavaSources code;
    /** The configuration a method runs with: its module's. */
    private final Function<Method, SpringConfig> config;
    /** Whether the app runs Kafka Connect, so some of its topics are filled by a connector rather than its code. */
    private final boolean connect;

    Detectors(JavaSources code, SpringConfig config) {
        this(code, m -> config, false);
    }

    private Detectors(JavaSources code, Function<Method, SpringConfig> config, boolean connect) {
        this.code = code;
        this.config = config;
        this.connect = connect;
    }

    /** The detectors for every module of the app, each judged by its own configuration. */
    static Detectors of(App app) {
        boolean connect = app.files().composeFiles().stream().anyMatch(f -> CONNECT_IMAGE.matcher(f.text()).find());
        return new Detectors(app.code(), app::configOf, connect);
    }

    /** Every candidate for every pattern, at most one per pattern and method. */
    List<Candidate> all() {
        var found = new LinkedHashMap<String, Candidate>();
        for (var c : List.of(p1(), p2(), p3(), p4(), p5(), p6(), p7(), p8(), p9()).stream().flatMap(List::stream).toList()) {
            found.putIfAbsent(c.pattern() + " " + c.method().handler(), c);
        }
        return List.copyOf(found.values());
    }

    List<Candidate> p1() {
        return code.kafkaListenersThatAcknowledge().stream()
            .map(m -> new Candidate("P1", m, "a Kafka listener that takes an Acknowledgment and calls acknowledge()")).toList();
    }

    List<Candidate> p2() {
        var found = new ArrayList<Candidate>();
        for (var listener : code.kafkaListeners()) {
            boolean autoCommit = config.apply(listener).get("spring.kafka.consumer.enable-auto-commit").map("true"::equals).orElse(false);
            // in the listener or in a method of its own class it calls, such as a batch listener's method for one record
            var handOffs = code.withOwnCallees(listener).stream().flatMap(m -> calls(m).stream()).filter(c -> HAND_OFF.contains(c.getNameAsString()))
                .map(c -> c.getScope().map(s -> s + ".").orElse("") + c.getNameAsString() + "(...)").distinct().toList();
            if (!handOffs.isEmpty()) {
                found.add(new Candidate("P2", listener, listenerKind(listener) + " that hands work to " + String.join(", ", handOffs)));
            } else if (autoCommit) {
                found.add(new Candidate("P2", listener, listenerKind(listener) + ", with spring.kafka.consumer.enable-auto-commit=true"));
            }
        }
        return found;
    }

    /**
     * "a Kafka listener", or for one that takes a whole poll "a batch Kafka listener (the container commits the whole
     * batch once it returns)".
     */
    private String listenerKind(Method listener) {
        return code.isBatchListener(listener) ? "a batch Kafka listener (the container commits the whole batch once it returns)" : "a Kafka listener";
    }

    List<Candidate> p3() {
        var found = new LinkedHashMap<String, Candidate>();
        for (var listener : code.kafkaListeners()) {
            var work = code.workOf(listener);
            if (work.isEmpty()) {
                found.putIfAbsent(listener.handler(), new Candidate("P3", listener, listenerKind(listener) + " that does its work itself"));
            }
            for (var m : work) {
                found.putIfAbsent(m.handler(), new Candidate("P3", m, "the method the Kafka listener " + listener.handler() + " calls to do its work", listener));
            }
        }
        return List.copyOf(found.values());
    }

    List<Candidate> p4() {
        var found = new ArrayList<Candidate>();
        for (var m : code.methods()) {
            var sql = code.withOwnCallees(m).stream().flatMap(x -> JavaSources.strings(x).stream()).toList();
            var read = new TreeSet<>(SqlTables.read(sql));
            var written = SqlTables.updated(sql);
            read.retainAll(written);
            if (!read.isEmpty()) {
                found.add(new Candidate("P4", m, "reads " + String.join(", ", read) + " with a select, then updates it"
                    + via(m)));
                continue;
            }
            var repos = new LinkedHashMap<String, Set<String>>();
            for (var c : calls(m)) {
                c.getScope().filter(s -> s.isNameExpr()).ifPresent(s -> repos.computeIfAbsent(s.toString(), k -> new TreeSet<>()).add(c.getNameAsString()));
            }
            repos.forEach((field, names) -> {
                boolean finds = names.stream().anyMatch(n -> n.startsWith("find") || n.startsWith("get"));
                boolean saves = names.contains("save") || names.contains("saveAndFlush");
                if (finds && saves) found.add(new Candidate("P4", m, "loads from " + field + ", then saves it back"));
            });
        }
        return found;
    }

    List<Candidate> p5() {
        var found = new ArrayList<Candidate>();
        for (var m : code.methods()) {
            Optional<String> client = calls(m).stream()
                .filter(c -> code.receiverType(m, c).filter(HTTP_CLIENTS::contains).isPresent() || HTTP_CALLS.contains(c.getNameAsString()))
                .map(c -> code.receiverType(m, c).orElse(c.getNameAsString())).findFirst();
            client.ifPresent(type -> found.add(new Candidate("P5", m, "calls an outside service over HTTP (" + type + ")")));
        }
        return found;
    }

    List<Candidate> p6() {
        var found = new ArrayList<Candidate>();
        for (var m : code.methods()) {
            var d = m.declaration();
            boolean retryLoop = d.findAll(Statement.class, Detectors::endless).stream().anyMatch(loop -> !loop.findAll(TryStmt.class).isEmpty());
            var retryTypes = d.findAll(ObjectCreationExpr.class).stream().map(o -> o.getType().getNameAsString())
                .filter(RETRY_TYPES::contains).distinct().toList();
            if (retryLoop) {
                found.add(new Candidate("P6", m, "a loop without an end condition that catches failures and goes round again"));
            } else if (!retryTypes.isEmpty() || d.getAnnotationByName("Retryable").isPresent()) {
                found.add(new Candidate("P6", m, "sets up retries (" + (retryTypes.isEmpty() ? "@Retryable" : String.join(", ", retryTypes)) + ")"));
            }
        }
        return found;
    }

    List<Candidate> p7() {
        var found = new ArrayList<Candidate>();
        for (var m : code.methods()) {
            var handlers = m.declaration().findAll(ObjectCreationExpr.class).stream().map(o -> o.getType().getNameAsString())
                .filter(ERROR_HANDLERS::contains).distinct().toList();
            boolean setsHandler = calls(m).stream().anyMatch(c -> c.getNameAsString().equals("setCommonErrorHandler"));
            if (!handlers.isEmpty() || setsHandler) {
                found.add(new Candidate("P7", m, "configures the Kafka listeners' error handling (" + (handlers.isEmpty() ? "setCommonErrorHandler" : String.join(", ", handlers)) + ")"));
            }
        }
        // error handling set up in a module covers its own listeners; set up in the repo's shared code, it covers all
        var configured = new HashSet<String>();
        found.forEach(c -> configured.add(c.method().module()));
        for (var listener : code.kafkaListeners()) {
            if (!configured.contains(listener.module()) && !configured.contains(null)) {
                found.add(new Candidate("P7", listener, listenerKind(listener) + ", and the app configures no error handling of its own"));
            }
        }
        return found;
    }

    List<Candidate> p8() {
        var found = new ArrayList<Candidate>();
        for (var m : code.methods()) {
            if (!publishes(m)) {
                // a @SendTo reply: Spring Kafka publishes what the listener returns, after its work, often done in another class
                var writes = code.replies(m) ? writes(m, 2) : Optional.<String>empty();
                writes.ifPresent(w -> found.add(new Candidate("P8", m, "a Kafka listener that writes %s and returns a reply, which @SendTo%s publishes once it has returned"
                    .formatted(w, code.replyTopics(m).isEmpty() ? "" : "(" + String.join(", ", code.replyTopics(m)) + ")"))));
                continue;
            }
            var d = m.declaration();
            if (d.getAnnotationByName("TransactionalEventListener").isPresent() || d.getAnnotationByName("EventListener").isPresent()) {
                found.add(new Candidate("P8", m, "an event listener that publishes once the code that raised the event has run" + via(m)));
                continue;
            }
            var sql = code.withOwnCallees(m).stream().flatMap(x -> JavaSources.strings(x).stream()).toList();
            var written = SqlTables.written(sql);
            boolean saves = calls(m).stream().anyMatch(c -> c.getNameAsString().startsWith("save"));
            if (!written.isEmpty() || saves) {
                found.add(new Candidate("P8", m, "writes " + (written.isEmpty() ? "to a repository" : String.join(", ", written)) + " and publishes" + via(m)));
            }
        }
        return found;
    }

    /**
     * Listeners on a topic no code of the app sends to, in an app that runs Kafka Connect: a connector fills it, such as
     * Debezium carrying an outbox to Kafka, and a connector delivers at least once.
     */
    List<Candidate> p9() {
        if (!connect) return List.of();
        var sent = new HashSet<String>();
        code.methods().forEach(m -> sent.addAll(code.sentTopics(m)));
        var found = new ArrayList<Candidate>();
        for (var e : code.endpoints()) {
            if (e.kind() != Endpoint.Kind.KAFKA || e.topics().isEmpty() || e.topics().stream().anyMatch(sent::contains)) continue;
            code.method(e.handler()).ifPresent(m -> found.add(new Candidate("P9", m, "%s on %s, which no code of the app sends to: Kafka Connect fills it, and delivers at least once"
                .formatted(listenerKind(m), String.join(", ", e.topics())))));
        }
        return found;
    }

    /**
     * What the method writes, itself, through the methods of its own class it calls, or through the app's methods it
     * calls up to {@code depth} calls deep: the tables its SQL writes, or "to a repository" for a save call.
     */
    private Optional<String> writes(Method m, int depth) {
        var own = code.withOwnCallees(m);
        var written = SqlTables.written(own.stream().flatMap(x -> JavaSources.strings(x).stream()).toList());
        if (!written.isEmpty()) return Optional.of(String.join(", ", written));
        if (own.stream().flatMap(x -> calls(x).stream()).anyMatch(c -> c.getNameAsString().startsWith("save"))) return Optional.of("to a repository");
        if (depth == 0) return Optional.empty();
        for (var callee : code.workOf(m)) {
            var w = writes(callee, depth - 1);
            if (w.isPresent()) return w;
        }
        return Optional.empty();
    }

    /** The app's methods that send through a Kafka producer or reply with @SendTo: where its messages, and their IDs, come from. */
    List<Method> publishers() {
        return code.methods().stream().filter(m -> !code.sends(m).isEmpty() || code.replies(m)).toList();
    }

    /** Whether the method, or a method of its own class it calls, sends through a Kafka producer. */
    private boolean publishes(Method m) {
        return code.withOwnCallees(m).stream().anyMatch(x -> !code.sends(x).isEmpty());
    }

    /** ", through read and write" when part of the work is in methods of the same class. */
    private String via(Method m) {
        var callees = code.withOwnCallees(m).stream().skip(1).map(x -> x.handler().substring(x.handler().indexOf('#') + 1)).toList();
        return callees.isEmpty() ? "" : " (through " + String.join(", ", callees) + ")";
    }

    private static List<MethodCallExpr> calls(Method m) {
        return m.declaration().findAll(MethodCallExpr.class);
    }

    /** for (;;), for (...; ; ...), while (true) and do ... while (true). */
    private static boolean endless(Statement s) {
        if (s instanceof ForStmt f) return f.getCompare().isEmpty();
        if (s instanceof WhileStmt w) return w.getCondition() instanceof BooleanLiteralExpr b && b.getValue();
        if (s instanceof DoStmt d) return d.getCondition() instanceof BooleanLiteralExpr b && b.getValue();
        return false;
    }

    /** Candidates by pattern, in pattern order. */
    static Map<String, List<Candidate>> byPattern(List<Candidate> candidates) {
        return candidates.stream().collect(Collectors.groupingBy(Candidate::pattern, LinkedHashMap::new, Collectors.toList()));
    }
}
