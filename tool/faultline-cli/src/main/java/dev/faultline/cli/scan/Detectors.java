package dev.faultline.cli.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
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

    private final JavaSources code;
    private final SpringConfig config;

    Detectors(JavaSources code, SpringConfig config) {
        this.code = code;
        this.config = config;
    }

    /** Every candidate for every pattern, at most one per pattern and method. */
    List<Candidate> all() {
        var found = new LinkedHashMap<String, Candidate>();
        for (var c : List.of(p1(), p2(), p3(), p4(), p5(), p6(), p7(), p8()).stream().flatMap(List::stream).toList()) {
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
        boolean autoCommit = config.get("spring.kafka.consumer.enable-auto-commit").map("true"::equals).orElse(false);
        for (var listener : code.kafkaListeners()) {
            var handOffs = calls(listener).stream().filter(c -> HAND_OFF.contains(c.getNameAsString()))
                .map(c -> c.getScope().map(s -> s + ".").orElse("") + c.getNameAsString() + "(...)").distinct().toList();
            if (!handOffs.isEmpty()) {
                found.add(new Candidate("P2", listener, "a Kafka listener that hands work to " + String.join(", ", handOffs)));
            } else if (autoCommit) {
                found.add(new Candidate("P2", listener, "a Kafka listener, with spring.kafka.consumer.enable-auto-commit=true"));
            }
        }
        return found;
    }

    List<Candidate> p3() {
        var found = new LinkedHashMap<String, Candidate>();
        for (var listener : code.kafkaListeners()) {
            var work = code.calledMethods(listener);
            if (work.isEmpty()) {
                found.putIfAbsent(listener.handler(), new Candidate("P3", listener, "a Kafka listener that does its work itself"));
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
        if (found.isEmpty()) {
            for (var listener : code.kafkaListeners()) {
                found.add(new Candidate("P7", listener, "a Kafka listener, and the app configures no error handling of its own"));
            }
        }
        return found;
    }

    List<Candidate> p8() {
        var found = new ArrayList<Candidate>();
        for (var m : code.methods()) {
            if (!publishes(m)) continue;
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

    /** The app's methods that send through a Kafka producer: where its messages, and their IDs, come from. */
    List<Method> publishers() {
        return code.methods().stream().filter(m -> !code.sends(m).isEmpty()).toList();
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
