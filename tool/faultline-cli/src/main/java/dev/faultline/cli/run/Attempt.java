package dev.faultline.cli.run;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.app.AppProcess;
import dev.faultline.cli.app.DockerStack;
import dev.faultline.cli.app.KafkaConnect;
import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.check.Checker;
import dev.faultline.cli.check.Destroyed;
import dev.faultline.cli.config.Settings;
import dev.faultline.cli.drain.Drainer;
import dev.faultline.cli.fault.Faults;
import dev.faultline.cli.fault.TimedFaults;
import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.history.HistoryEntry.Status;
import dev.faultline.cli.history.HistoryLog;
import dev.faultline.cli.kafka.DownstreamReader;
import dev.faultline.cli.kafka.KafkaLag;
import dev.faultline.cli.network.Toxiproxy;
import dev.faultline.cli.plan.Durations;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.relay.ChaosRelay;
import dev.faultline.cli.stub.StubControl;
import dev.faultline.cli.trace.Traces;
import dev.faultline.cli.traffic.TrafficSender;
import dev.faultline.cli.workload.EffectsTable;
import dev.faultline.cli.workload.EffectsWatcher;
import dev.faultline.cli.workload.Request;
import dev.faultline.cli.workload.Workload;

/**
 * One run of a plan in its own folder: start the app (a jar, Docker Compose services, or one already running),
 * send the traffic while the fault fires, wait until the app has finished, and check every request's effect.
 */
final class Attempt {
    static final String RESULT_FILE = "attempt.json";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final int SHOW_OFFENDERS = 10;
    /** Warm-up requests use the plan's seed plus this, so their IDs never match the run's own. */
    private static final long WARMUP_SEED_OFFSET = 1_000_000;

    private final String name;
    private final String kind;
    private final Plan plan;
    private final Path dir;
    private final Path agentJar;
    private final PrintWriter out;
    private AppProcess process;
    private DockerStack stack;
    private ChaosRelay relay;
    /** How long nothing may change before a journey through Kafka Connect counts as done: a connector resumes in batches. */
    static final Duration CONNECT_QUIET = Duration.ofSeconds(15);
    /** The Kafka Connect connectors that ran once the app was ready; the drain waits until as many run again. */
    private int connectors;
    private DownstreamReader downstream;
    private EffectsWatcher watcher;

    /** The run's traces, when it keeps them; else null. */
    private final Traces traces;

    private Attempt(String name, String kind, Plan plan, Path dir, Path agentJar, Traces traces, PrintWriter out) {
        this.name = name;
        this.kind = kind;
        this.plan = plan;
        this.dir = dir;
        this.agentJar = agentJar;
        this.traces = traces;
        this.out = out;
    }

    static AttemptResult run(String name, String kind, Plan plan, Path dir, Path agentJar, PrintWriter out) throws Exception {
        return run(name, kind, plan, dir, agentJar, null, out);
    }

    /** @param traces the run's traces, or null to keep none */
    static AttemptResult run(String name, String kind, Plan plan, Path dir, Path agentJar, Traces traces, PrintWriter out) throws Exception {
        return new Attempt(name, kind, plan, dir, agentJar, traces, out).run();
    }

    private AttemptResult run() throws Exception {
        Files.createDirectories(dir);
        var traffic = plan.traffic();
        var request = traffic.request();
        var requests = Workload.generate(plan.template(), plan.correlateBy().requestField(), traffic.seed(), traffic.count());
        var effects = new EffectsTable(plan.correlateBy().table(), plan.correlateBy().column());
        var fault = plan.fault();
        Duration maxWait = maxWait();
        for (var db : plan.allDatabases()) {
            if (db.seedFile() != null && !Files.isRegularFile(Path.of(db.seedFile()))) throw new IllegalStateException("database.seed_file: no file at " + db.seedFile());
        }

        try (var databases = Databases.open(plan);
             var sender = new TrafficSender(URI.create(plan.target().url()), headers(plan), request.contentType(), plan.correlateBy().responseField());
             var lag = new KafkaLag(plan.kafka().bootstrap(), plan.kafka().group(), plan.kafka().topic(), plan.kafka().otherGroups());
             var toxiproxy = plan.toxiproxy() == null ? null : new Toxiproxy(plan.toxiproxy());
             var stub = plan.stub() == null ? null : new StubControl(plan.stub())) {
            Connection conn = databases.of(plan.effectsDatabase());
            if (traces != null) sender.sendTraceparents();
            try {
                prepare(sender, lag);
                reset(databases);
                startApp(sender, lag, toxiproxy, stub);
                seed(databases);
                if (toxiproxy != null) toxiproxy.reset();
                if (stub != null) stub.reset();
                startRelay();
                warmUp(databases, sender, lag, effects);
                arm(toxiproxy, stub);
                if (fault != null && "traffic".equals(fault.layer())) requests = duplicateSome(requests, fault);
                startWatching(effects);

                TimedFaults timed = fault != null && fault.timed()
                    ? new TimedFaults(fault, stack, toxiproxy, stub, dir, line -> Out.line(out, "%s", line)) : null;
                Out.line(out, "send      %,d x %s %s at %d/s, seed %d", requests.size(), request.method(), request.path(), traffic.rate(), traffic.seed());
                Path historyFile = dir.resolve("history.jsonl");
                long sendMillis;
                try (var history = HistoryLog.create(historyFile)) {
                    if (timed != null) timed.start();
                    sendMillis = sender.send(requests, request.method(), request.path(), traffic.rate(), history).toMillis();
                    if (timed != null) timed.awaitDone();
                } finally {
                    if (timed != null) timed.close();
                }
                List<HistoryEntry> entries = HistoryLog.read(historyFile);
                Map<Status, Long> byStatus = entries.stream().collect(groupingBy(HistoryEntry::status, counting()));
                var sent = new AttemptResult.Sent(byStatus.getOrDefault(Status.OK, 0L), byStatus.getOrDefault(Status.FAIL, 0L),
                    byStatus.getOrDefault(Status.INFO, 0L), sendMillis);
                Out.line(out, "          done in %.1fs   ok %d, fail %d, info %d", sendMillis / 1000.0, sent.ok(), sent.fail(), sent.info());

                var drain = drain(conn, effects, lag, maxWait);
                Instant faultEnded = timed == null ? null : timed.lastEnded();
                if (fault != null && "service".equals(fault.layer()) && !fault.timed()) {
                    for (var f : stub.fired()) Faults.logFired(dir, "service", f.action(), f.paymentId());
                }
                Integer restarts = process != null ? Integer.valueOf(process.restarts()) : stack != null ? Integer.valueOf(stack.restarts()) : null;
                closeApp();

                var ids = Correlation.of(plan.correlateBy(), entries);
                Map<String, Instant> firstApplied = watcher == null ? Map.of() : ids.firstApplied(watcher.stop());
                // what a binary message may carry: a request's ID, or the one the app answered it with
                var known = new HashSet<String>();
                for (var e : entries) {
                    known.add(e.id());
                    if (e.effect() != null) known.add(e.effect());
                }
                Set<String> published = downstream == null ? Set.of() : ids.requests(downstream.requestIds(known));
                Destroyed destroyed = relay == null ? Destroyed.NONE : relay.destroyed(known);
                if (destroyed.unreadable() > 0) {
                    Out.line(out, "relay     %d corrupted message(s) name no request of this run that Faultline can find in their bytes", destroyed.unreadable());
                }
                closeWatchers();
                var checks = check(databases, effects, ids, entries, requests, destroyed, published, firstApplied, faultEnded);
                Integer fired = null;
                if (fault != null) {
                    fired = Faults.fired(dir);
                    Out.line(out, "faults    %d of %d fired (faults.jsonl)%s", fired, fault.count(),
                        restarts == null ? "" : ", app restarted %d times".formatted(restarts));
                    if (plan.checks().contains(Plan.FAULTS_FIRED)) checks.add(Checker.faultsFired(fault.count(), fired));
                }
                printChecks(checks);
                var result = new AttemptResult(name, kind, sent, drain, restarts, fired, checks);
                Json.writePretty(dir.resolve(RESULT_FILE), result);
                return result;
            } finally {
                closeWatchers();
                closeApp();
            }
        }
    }

    /**
     * Waits until Kafka lag (the app's, its other groups' and the relay's) is 0 and the effects table and downstream
     * topic stop changing. With Kafka Connect in the journey, also until its connectors run again (after a fault that
     * stopped it) and its topics stop growing: it publishes on its own, which no consumer group's lag shows.
     */
    private AttemptResult.Drain drain(Connection conn, EffectsTable effects, KafkaLag lag, Duration maxWait) throws Exception {
        String connectUrl = plan.target().compose() == null ? null : plan.target().compose().connect();
        // a connector polls its source and stores its position every few seconds, so 3s of quiet can fall between two of its batches
        Duration quiet = connectUrl == null ? Drainer.QUIET : CONNECT_QUIET;
        Out.line(out, "drain     waiting until Kafka lag is 0 and %s%s stops changing%s ...", effects.table(),
            downstream == null ? "" : " and " + plan.downstream().topic(),
            connectUrl == null ? "" : ", and Kafka Connect's %d connectors run".formatted(connectors));
        var relayLag = relayLag();
        Drainer.Result drained;
        try (var connect = connectUrl == null ? null : new KafkaConnect(connectUrl)) {
            drained = Drainer.waitUntilDone(() -> {
                if (process != null) process.checkHealthy();
                if (relay != null) relay.checkHealthy();
                if (downstream != null) downstream.checkHealthy();
                return effects.rows(conn) + (downstream == null ? 0 : downstream.events()) + (connect == null ? 0 : lag.published());
            }, () -> lag.lag() + (relayLag == null ? 0 : relayLag.lag()) + (connect != null && connect.running() < connectors ? 1 : 0),
                quiet, maxWait, Drainer.POLL);
        } finally {
            if (relayLag != null) relayLag.close();
        }
        if (drained.settled()) {
            Out.line(out, "          %,d rows%s, lag 0, unchanged for %ds", effects.rows(conn),
                downstream == null ? "" : ", %,d events".formatted(downstream.events()), quiet.toSeconds());
        } else {
            Out.line(out, "          gave up after %ds with lag %d and %,d rows: 'lost' results may be false",
                maxWait.toSeconds(), drained.backlog(), effects.rows(conn));
        }
        return new AttemptResult.Drain(effects.rows(conn), drained.backlog(), drained.settled(), drained.waited().toMillis());
    }

    private List<CheckResult> check(Databases databases, EffectsTable effects, Correlation ids, List<HistoryEntry> entries, List<Request> requests,
                                    Destroyed destroyed, Set<String> published, Map<String, Instant> firstApplied, Instant faultEnded) throws Exception {
        var checks = new ArrayList<CheckResult>();
        Connection conn = databases.of(plan.effectsDatabase());
        var applied = ids.applied(effects.appliedCounts(conn));
        if (ids.unmatched() > 0) {
            Out.line(out, "correlate %,d effect rows belong to requests that got no answer, so there is no ID to match them by", ids.unmatched());
        }
        for (var c : Checker.check(entries, applied, destroyed)) {
            if (plan.checks().contains(c.name())) checks.add(c);
        }
        if (plan.checks().contains(Plan.PER_KEY_ORDER)) {
            var keyOf = new HashMap<String, String>();
            for (var r : requests) keyOf.putIfAbsent(r.id(), Workload.idOf(r.body(), plan.order().key()));
            checks.add(Checker.perKeyOrder(entries, keyOf, ids.order(effects.appliedOrder(conn, plan.order().by()))));
        }
        if (plan.checks().contains(Plan.NO_LOST_DOWNSTREAM)) checks.add(Checker.noLostDownstream(applied, published, plan.downstream().topic()));
        if (plan.checks().contains(Plan.RECOVERY_WITHIN)) {
            checks.add(Checker.recoveryWithin(entries, faultEnded, Durations.parse(plan.recoveryWithin()), firstApplied));
        }
        for (var rule : plan.rules() == null ? List.<Plan.Rule>of() : plan.rules()) {
            try {
                checks.add(Checker.rule(rule.id(), EffectsTable.rows(databases.of(plan.databaseNamed(rule.database())), rule.sql())));
            } catch (SQLException e) {
                // a person's rule that does not run is a broken plan; the LLM's is one more wrong rule, dropped like the rest
                if (!rule.byLlm()) throw new IllegalStateException("rule " + rule.id() + " does not run: " + e.getMessage(), e);
                checks.add(Checker.brokenRule(rule.id(), e.getMessage()));
            }
        }
        return checks;
    }

    /** A client that submits some requests twice: every nth one is sent again right after itself, until it has done so times times. */
    private List<Request> duplicateSome(List<Request> requests, Plan.Fault fault) throws Exception {
        var out = new ArrayList<Request>();
        int copies = 0;
        for (var r : requests) {
            out.add(new Request(out.size(), r.id(), r.body()));
            if ((r.index() + 1) % fault.nthHit() == 0 && copies < fault.count()) {
                out.add(new Request(out.size(), r.id(), r.body()));
                Faults.logFired(dir, "traffic", "duplicate", r.id());
                copies++;
            }
        }
        return out;
    }

    /** The chaos relay's lag, for the drain, or null without a relay. */
    private KafkaLag relayLag() {
        return relay == null ? null : new KafkaLag(plan.kafka().bootstrap(), relay.group(), plan.relay().from());
    }

    /**
     * Sends the warm-up requests (other IDs than the run's: the same template with another seed), waits until the app
     * has finished them, and resets the database again. Nothing fires meanwhile: the agent and the relay are not armed yet.
     */
    private void warmUp(Databases databases, TrafficSender sender, KafkaLag lag, EffectsTable effects) throws Exception {
        var traffic = plan.traffic();
        int n = traffic.warmupCount();
        if (n == 0) return;
        Connection conn = databases.of(plan.effectsDatabase());
        var requests = Workload.generate(plan.template(), plan.correlateBy().requestField(), traffic.seed() + WARMUP_SEED_OFFSET, n);
        Out.line(out, "warm-up   %,d requests at %d/s, then the database is reset again", n, traffic.rate());
        try (var history = HistoryLog.create(dir.resolve("warmup.jsonl"))) {
            sender.send(requests, traffic.request().method(), traffic.request().path(), traffic.rate(), history);
        }
        var relayLag = relayLag();
        try {
            var drained = Drainer.waitUntilDone(() -> effects.rows(conn), () -> lag.lag() + (relayLag == null ? 0 : relayLag.lag()));
            if (!drained.settled()) throw new IllegalStateException("the app did not finish its warm-up requests (lag " + drained.backlog() + ")");
        } finally {
            if (relayLag != null) relayLag.close();
        }
        reset(databases);
        seed(databases);
    }

    /**
     * Arms what counts toward the fault: the agent and the relay (if a warm-up kept them waiting), and the stub's
     * nth-charge faults. A network fault's proxy must exist; it is broken later, on the fault's schedule.
     */
    private void arm(Toxiproxy toxiproxy, StubControl stub) throws Exception {
        var fault = plan.fault();
        if (relay != null) relay.armed(true);
        if (fault == null) return;
        if (fault.inCode() && plan.traffic().warmupCount() > 0) Faults.arm(dir);
        if ("network".equals(fault.layer())) toxiproxy.checkProxy(fault.target());
        if ("service".equals(fault.layer()) && !fault.timed()) {
            Duration delay = fault.duration() == null ? Duration.ZERO : Durations.parse(fault.duration());
            stub.arm(fault.action(), fault.nthHit(), fault.count(), delay);
            Out.line(out, "fault     the stub will %s every %dth charge, %d times%s", fault.action().replace('-', ' '), fault.nthHit(), fault.count(),
                delay.isZero() ? "" : ", by " + fault.duration());
        }
    }

    /** The chaos relay, before any traffic; it waits to be armed when there is a warm-up first. */
    private void startRelay() throws Exception {
        if (plan.relay() == null) return;
        relay = new ChaosRelay(plan.kafka().bootstrap(), plan.relay(), plan.fault(), plan.correlateBy().requestField(), dir, line -> Out.line(out, "%s", line));
        relay.armed(plan.traffic().warmupCount() == 0);
        relay.start();
    }

    /** What watches the run from outside: the downstream topic and the effects table. */
    private void startWatching(EffectsTable effects) throws Exception {
        if (plan.downstream() != null) {
            downstream = new DownstreamReader(plan.kafka().bootstrap(), plan.downstream());
            downstream.start();
        }
        if (plan.checks().contains(Plan.RECOVERY_WITHIN)) {
            var db = plan.effectsDatabase();
            watcher = new EffectsWatcher(DriverManager.getConnection(db.url(), db.user(), db.password()), effects);
            watcher.start();
        }
    }

    private void closeWatchers() throws Exception {
        if (relay != null) relay.close();
        if (downstream != null) downstream.close();
        if (watcher != null) watcher.close();
        relay = null;
        downstream = null;
        watcher = null;
    }

    /** Checks Kafka answers, then makes sure no messages from an earlier run will be processed during this one. */
    private void prepare(TrafficSender sender, KafkaLag lag) throws Exception {
        long backlog;
        try {
            backlog = lag.lag();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the consumer lag from Kafka at " + plan.kafka().bootstrap(), e);
        }
        var target = plan.target();
        if (target.compose() != null) {
            stack = new DockerStack(target.compose(), dir);
            stack.removeLeftovers();
        } else if (target.jar() != null && sender.isReachable()) {
            throw new IllegalStateException("something is already answering at " + target.url()
                + ": stop it, or remove target.jar from the plan to test the app that is running");
        }
        if (target.compose() != null || target.jar() != null) {
            long skipped = lag.skipBacklog();
            if (skipped > 0) Out.line(out, "kafka     skipped %,d leftover messages from an earlier run", skipped);
        } else {
            sender.checkReachable();
            if (backlog > 0) {
                Out.line(out, "kafka     waiting for the app to finish %,d leftover messages from an earlier run ...", backlog);
                var settled = Drainer.waitUntilDone(() -> 0, lag::lag);
                if (!settled.settled()) throw new IllegalStateException("the app still has " + settled.backlog() + " leftover messages");
            }
        }
    }

    /** Puts every database of the plan back to its starting state, each in one transaction. */
    private void reset(Databases databases) throws Exception {
        int ran = 0;
        int skipped = 0;
        for (var db : plan.allDatabases()) {
            var reset = EffectsTable.reset(databases.of(db), db.resetStatements());
            ran += reset.ran();
            skipped += reset.skipped().size();
        }
        String in = plan.allDatabases().size() > 1 ? " in %d databases".formatted(plan.allDatabases().size()) : "";
        if (skipped > 0 && ran == 0) {
            Out.line(out, "reset     fresh database%s: the app creates its tables when it starts", in.isEmpty() ? "" : "s");
        } else {
            Out.line(out, "reset     %d statements%s%s", ran, in, skipped == 0 ? "" : " (%d skipped: table not created yet)".formatted(skipped));
        }
        var flushed = plan.target().compose() == null ? List.<String>of() : plan.target().compose().flushed();
        for (String service : flushed) stack.flush(service);
        if (!flushed.isEmpty()) Out.line(out, "          and emptied %s, the cache the app keeps state in", String.join(", ", flushed));
    }

    /** Loads the plan's test data: after a reset, once the app has created its tables. */
    private void seed(Databases databases) throws Exception {
        for (var db : plan.allDatabases()) {
            String file = db.seedFile();
            if (file == null) continue;
            try (var st = databases.of(db).createStatement()) {
                st.execute(withSeed(Files.readString(Path.of(file), StandardCharsets.UTF_8), plan.traffic().seed()));
            } catch (SQLException e) {
                throw new IllegalStateException("database.seed_file " + file + " failed: " + e.getMessage()
                    + " (it runs again after every reset, so write it like insert ... on conflict do nothing)", e);
            }
            Out.line(out, "seed      loaded %s", file);
        }
    }

    private static final Pattern SEED = Pattern.compile("\\$\\{seed(?::(\\d{1,2}))?}");

    /**
     * A seed file with ${seed} as the run's seed, and ${seed:N} padded with zeros to N digits as a request template pads it:
     * test data for the IDs the requests use, such as a cart for each user 00000000-0000-0000-${seq:4}-${seed:12} makes.
     */
    static String withSeed(String sql, Long seed) {
        if (seed == null) return sql;
        return SEED.matcher(sql).replaceAll(m -> {
            String digits = Long.toString(seed);
            int width = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
            return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
        });
    }

    /** The auth header, with its ${env:NAME} read from the environment or .env. */
    static Map<String, String> headers(Plan plan) {
        var auth = plan.target().auth();
        if (auth == null) return Map.of();
        var settings = Settings.load();
        String value = Plan.Auth.ENV.matcher(auth.value()).replaceAll(m -> Matcher.quoteReplacement(
            settings.require(m.group(1), "target.auth.value needs it: set it in the environment or in .env")));
        return Map.of(auth.header(), value);
    }

    private void startApp(TrafficSender sender, KafkaLag lag, Toxiproxy toxiproxy, StubControl stub) throws Exception {
        var target = plan.target();
        var fault = plan.fault();
        boolean agent = fault != null && fault.inCode();
        boolean armLater = plan.traffic().warmupCount() > 0;
        // the services whose sends to the relayed topic the agent renames, when the code names the topic
        var senders = plan.relay() == null ? List.<String>of() : plan.relay().senderServices();
        String rename = plan.relay() == null ? null : plan.relay().rename();
        if ((agent || !senders.isEmpty()) && !Files.isRegularFile(agentJar)) {
            throw new IllegalStateException("fault agent not found at " + agentJar + ": build it with (cd tool && ./mvnw -q package -DskipTests)");
        }

        if (target.compose() != null) {
            var agents = new LinkedHashMap<String, Map<String, String>>();
            if (agent) {
                agents.put(fault.target(), Faults.writeAgentPlan(fault, dir, DockerStack.RUN_DIR_IN_CONTAINER, armLater,
                    senders.contains(fault.target()) ? rename : null));
                for (String partner : fault.partnerServices()) agents.put(partner, Faults.writePartnerPlan(fault, dir, DockerStack.RUN_DIR_IN_CONTAINER));
            }
            String faulted = String.join(", ", agents.keySet());
            for (String s : senders) {
                if (!agents.containsKey(s)) agents.put(s, Faults.writeRenamePlan(rename, dir, DockerStack.RUN_DIR_IN_CONTAINER));
            }
            Out.line(out, "app       starting %s with Docker Compose%s%s ...", String.join(", ", target.compose().services()),
                agent ? ", the fault agent on " + faulted : "",
                senders.isEmpty() ? "" : ", the agent in %s sending to %s instead of %s".formatted(String.join(", ", senders), plan.relay().from(), plan.relay().to()));
            if (traces != null) stack.keepTraces(traces.agentJar());
            stack.up(plan.appSettings(), agents, agentJar);
            // building the images is not starting up: an app whose Dockerfiles build its jars takes minutes to build
            long start = System.nanoTime();
            int consumers = target.compose().consumers() == null ? 0 : target.compose().consumers();
            try (var connect = target.compose().connect() == null ? null : new KafkaConnect(target.compose().connect())) {
                int connectors = 0;
                while (!sender.isReachable() || consumers > 0 && lag.stableMembers() < consumers || !helpersReady(toxiproxy, stub)
                    || connect != null && (connectors = connect.running()) == 0) {
                    waitOrGiveUp(start);
                }
                Out.line(out, "          ready after %.1fs%s%s", (System.nanoTime() - start) / 1e9, consumers > 0 ? ", " + consumers + " consumers in the group" : "",
                    connect == null ? "" : ", %d connectors running".formatted(connectors));
                this.connectors = connectors;
            }
        } else if (target.jar() != null) {
            Path appJar = Path.of(target.jar());
            if (!Files.isRegularFile(appJar)) {
                throw new IllegalStateException("app jar not found: " + Out.path(appJar) + " (build the app first, for example ./mvnw -q package -DskipTests)");
            }
            var env = agent ? Faults.writeAgentPlan(fault, dir, dir.toAbsolutePath().toString(), armLater) : Map.<String, String>of();
            process = new AppProcess(AppProcess.command(appJar, agent ? agentJar : null, target.args()), env, dir.resolve("app.log"),
                line -> Out.line(out, "%s", line));
            process.start();
            Out.line(out, "app       started %s (pid %d)%s, waiting for it to answer ...", appJar.getFileName(), process.pid(),
                agent ? " with the fault agent" : "");
            long start = System.nanoTime();
            while (!sender.isReachable() || !helpersReady(toxiproxy, stub)) {
                process.checkHealthy();
                waitOrGiveUp(start);
            }
            Out.line(out, "          ready after %.1fs", (System.nanoTime() - start) / 1e9);
        } else if (!helpersReady(toxiproxy, stub)) {
            throw new IllegalStateException("the plan's toxiproxy or stub is not answering");
        }
    }

    /** Whether Toxiproxy and the stub answer, when the plan uses them. */
    private static boolean helpersReady(Toxiproxy toxiproxy, StubControl stub) throws InterruptedException {
        if (stub != null && !stub.isReachable()) return false;
        if (toxiproxy == null) return true;
        try {
            toxiproxy.reset();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Duration maxWait() {
        return plan.traffic().drainTimeout() == null ? Drainer.MAX_WAIT : Durations.parse(plan.traffic().drainTimeout());
    }

    private void waitOrGiveUp(long start) throws InterruptedException {
        if (System.nanoTime() - start > STARTUP_TIMEOUT.toNanos()) {
            throw new IllegalStateException("the app was not ready within " + STARTUP_TIMEOUT.toSeconds() + "s (see app.log)");
        }
        Thread.sleep(500);
    }

    private void closeApp() {
        if (process != null) process.close();
        if (stack != null) stack.close();
        process = null;
        stack = null;
    }

    private void printChecks(List<CheckResult> checks) {
        boolean first = true;
        for (var c : checks) {
            Out.line(out, "%-9s %-20s %s%s", first ? "check" : "", c.name(), c.passed() ? "PASS" : "FAIL (" + c.offenders().size() + ")",
                c.note() == null ? "" : "   " + c.note());
            for (var offender : c.offenders().stream().limit(SHOW_OFFENDERS).toList()) {
                Out.line(out, "%-30s %s", "", offender);
            }
            if (c.offenders().size() > SHOW_OFFENDERS) {
                Out.line(out, "%-30s ... and %d more (see %s)", "", c.offenders().size() - SHOW_OFFENDERS, RESULT_FILE);
            }
            first = false;
        }
    }
}
