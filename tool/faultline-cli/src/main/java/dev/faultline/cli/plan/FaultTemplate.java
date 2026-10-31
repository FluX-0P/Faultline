package dev.faultline.cli.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.Endpoint;
import dev.faultline.cli.source.JavaSources;
import dev.faultline.cli.source.JavaSources.Method;
import dev.faultline.cli.source.SqlTables;

/**
 * The fault each risky pattern is tested with, and what goes with it: the checks that see the outcome, traffic that
 * leaves the fault room to fire, and the helpers it needs. Each breaks the app exactly where its pattern says the data
 * goes wrong, at a place Faultline finds in the code, so the plan writes itself; the LLM only drafts the requests and
 * the rules.
 *
 * <ul>
 * <li>P1 save, then acknowledge: crash just before the acknowledgment, so Kafka delivers the message again.
 * <li>P2 work handed off: crash the moment the listener returns, while the work it handed off may still be running.
 * <li>P3 no duplicate check: the client submits every 50th request twice.
 * <li>P4 read, modify, save: hold one worker between its read and its write until another worker has written.
 * <li>P5 outside call without a key: the provider charges, then replies after the caller has given up.
 * <li>P6 retries without backoff: the provider goes down for a while, and stays down while it gets too many requests.
 * <li>P7 no dead-letter handling: the relay corrupts one message.
 * <li>P8 save, then publish: crash just before the publish.
 * </ul>
 *
 * The numbers are the ones the hand-written benchmark plans proved: a restarted worker takes about 10s to rejoin, so
 * two crashes need 3000 requests at 100 a second.
 */
final class FaultTemplate {
    static final long CRASH_EVERY = 150;
    static final int CRASHES = 2;
    static final String RELAY_SUFFIX = ".faultline";
    static final Plan.Rule ONE_CHARGE_AT_THE_PROVIDER = new Plan.Rule("one-charge-per-payment-at-the-provider",
        "select payment_id, count(*) as charges from stub.charges group by payment_id having count(*) > 1");

    /**
     * A template applied to one risk: what it does, in words, and the parts of the plan it decides. {@code settings} are
     * app settings the run needs (such as the topic a relay sits in front of), {@code downstreamTopic} the topic
     * no-lost-downstream reads, {@code stub} whether it breaks the fake provider.
     */
    record Test(String does, Plan.Fault fault, List<String> checks, Traffic traffic, Plan.Relay relay, Map<String, String> settings,
                String downstreamTopic, String recoveryWithin, List<Plan.Rule> rules, boolean stub) {}

    /** {@code warmup} and {@code drainTimeout} may be null. */
    record Traffic(int count, int rate, Integer warmup, String drainTimeout) {}

    /** The methods a read-modify-write reads the row with and writes it back with. */
    record ReadWrite(Method read, Method write, String table) {}

    private FaultTemplate() {}

    /**
     * The test for a risk, given the listener that receives the journey's messages and its topic.
     *
     * @throws IllegalStateException when the risk cannot be tested this way, saying why
     */
    static Test of(Risk risk, App app, Deployment d, Method listener, String topic) {
        String on = d.faultTarget() == null ? "the app" : d.faultTarget();
        var crashTraffic = new Traffic(3000, 100, null, null);
        return switch (risk.pattern() == null ? "" : risk.pattern()) {
            case "P1" -> new Test("crash %s just before it acknowledges a message, on every %dth acknowledgment, %d times".formatted(on, CRASH_EVERY, CRASHES),
                new Plan.Fault(null, "before-ack", null, "crash", CRASH_EVERY, CRASHES, null, d.faultTarget(), null),
                checks(), crashTraffic, null, Map.of(), null, null, List.of(), false);
            case "P2" -> {
                String point = isListener(app, risk.location()) ? risk.location() : listener.handler();
                yield new Test("crash %s the moment %s returns, on every %dth message, %d times: what it handed off is still running".formatted(on, point, CRASH_EVERY, CRASHES),
                    new Plan.Fault(null, point, "after", "crash", CRASH_EVERY, CRASHES, null, d.faultTarget(), null),
                    checks(), crashTraffic, null, Map.of(), null, null, List.of(), false);
            }
            case "P3" -> new Test("send every 50th request twice, like a client that submits again, 20 times",
                new Plan.Fault("traffic", null, null, "duplicate", 50L, 20, null, null, null),
                checks(), new Traffic(2000, 100, null, null), null, Map.of(), null, null, List.of(), false);
            case "P4" -> p4(risk, app, d);
            case "P5" -> {
                needStub(risk, d);
                yield new Test("make the fake provider charge every 100th charge, then reply 3s later, after the caller has given up, 5 times",
                    new Plan.Fault("service", null, null, "late-reply", 100L, 5, "3s", null, null),
                    checks(), new Traffic(2000, 100, null, null), null, Map.of(), null, null, List.of(ONE_CHARGE_AT_THE_PROVIDER), true);
            }
            case "P6" -> {
                needStub(risk, d);
                // an app that never catches up never drains; a healthy one is done well within 60s
                yield new Test("take the fake provider down 5s into the traffic for 6s; it stays down while it gets more than 50 requests a second",
                    new Plan.Fault("service", null, null, "outage", null, null, "6s", null, "5s"),
                    checks(Plan.RECOVERY_WITHIN), new Traffic(2000, 100, null, "60s"), null, Map.of(), null, "30s", List.of(), true);
            }
            case "P7" -> p7(risk, app, listener, topic);
            case "P8" -> p8(risk, app, d, on);
            default -> throw new IllegalStateException("there is no fault template for pattern " + risk.pattern() + " yet");
        };
    }

    /** A barrier: one worker reads, then waits until another worker has written the same row. */
    private static Test p4(Risk risk, App app, Deployment d) {
        if (d.consumers().size() < 2) {
            throw new IllegalStateException("testing %s at %s takes two workers racing, so the app must run as at least two Kafka consumers (plan --compose)"
                .formatted(risk.pattern(), risk.location()));
        }
        Method m = method(app, risk);
        var rw = readAndWrite(app.code(), m).orElseThrow(() -> new IllegalStateException(
            "cannot test %s at %s: its read and its write are not separate methods, so there is nowhere to hold a worker between them"
                .formatted(risk.pattern(), risk.location())));
        var partners = d.consumers().stream().filter(c -> !c.equals(d.faultTarget())).toList();
        // the payments go out 200ms apart, so without the barrier no two overlap; the warm-up first gets the workers
        // past their slow first requests, which would
        return new Test("hold %s after %s, on every 5th call, 3 times, until %s has returned from %s, for up to 3s: both write %s from the same read"
                .formatted(d.faultTarget(), rw.read().handler(), String.join(" or ", partners), rw.write().handler(), rw.table()),
            new Plan.Fault(null, rw.read().handler(), "after", "barrier", 5L, 3, "3s", d.faultTarget(), null, rw.write().handler(), partners, null),
            checks(), new Traffic(100, 5, 30, null), null, Map.of(), null, null, List.of(), false);
    }

    /** The relay sits between the topic the app sends to and the one its listener reads, and corrupts one message. */
    private static Test p7(Risk risk, App app, Method listener, String topic) {
        String key = app.code().topicSetting(topic).orElseThrow(() -> new IllegalStateException(
            "cannot test %s at %s: the app sends to %s under a fixed name, so Faultline cannot put its relay in front of %s"
                .formatted(risk.pattern(), risk.location(), topic, listener.handler())));
        String from = topic + RELAY_SUFFIX;
        // the setting must move the sender only: if the listener read it too, the relay would forward to a topic nobody reads
        var moved = app.withSettings(Map.of(key, from)).code().endpoints().stream()
            .filter(e -> e.kind() == Endpoint.Kind.KAFKA && e.handler().equals(listener.handler())).findFirst();
        if (moved.isEmpty() || !moved.get().topics().contains(topic)) {
            throw new IllegalStateException("cannot test %s at %s: %s sets the topic %s reads too, so a relay cannot sit in between"
                .formatted(risk.pattern(), risk.location(), key, listener.handler()));
        }
        // a blocked partition never drains; 30s is plenty for a healthy app
        return new Test("send the app's messages through Faultline's relay (%s=%s) and corrupt the 300th, once".formatted(key, from),
            new Plan.Fault("messaging", null, null, "corrupt", 300L, 1, null, null, null),
            checks(), new Traffic(2000, 100, null, "30s"), new Plan.Relay(from, topic), Map.of(key, from), null, null, List.of(), false);
    }

    /** A crash just before the method that sends the event. */
    private static Test p8(Risk risk, App app, Deployment d, String on) {
        Method m = method(app, risk);
        var own = app.code().withOwnCallees(m);
        // the method that makes the send, deepest first: a crash before the risky method itself could come before the save
        Method sender = own.stream().skip(1).filter(x -> !app.code().sends(x).isEmpty()).findFirst()
            .orElse(app.code().sends(m).isEmpty() ? null : m);
        if (sender == null) throw new IllegalStateException("cannot test %s at %s: it publishes nothing Faultline can see".formatted(risk.pattern(), risk.location()));
        var topics = app.code().sentTopics(sender);
        if (topics.isEmpty()) throw new IllegalStateException("cannot tell which topic %s publishes to".formatted(sender.handler()));
        return new Test("crash %s just before %s publishes, on every %dth event, %d times".formatted(on, sender.handler(), 100, CRASHES),
            new Plan.Fault(null, sender.handler(), "before", "crash", 100L, CRASHES, null, d.faultTarget(), null),
            checks(Plan.NO_LOST_DOWNSTREAM), new Traffic(3000, 100, null, null), null, Map.of(), topics.getFirst(), null, List.of(), false);
    }

    /**
     * Among the methods the risky one calls (its own class's, and other classes' through a field), one that selects
     * from a table and another that updates the same table.
     */
    static Optional<ReadWrite> readAndWrite(JavaSources code, Method m) {
        var steps = new ArrayList<>(code.withOwnCallees(m).subList(1, code.withOwnCallees(m).size()));
        steps.addAll(code.calledMethods(m));
        for (var read : steps) {
            for (var write : steps) {
                if (read.handler().equals(write.handler())) continue;
                var tables = new TreeSet<>(SqlTables.read(JavaSources.strings(read)));
                tables.retainAll(SqlTables.updated(JavaSources.strings(write)));
                if (!tables.isEmpty()) return Optional.of(new ReadWrite(read, write, tables.first()));
            }
        }
        return Optional.empty();
    }

    private static void needStub(Risk risk, Deployment d) {
        if (d.stub() == null) {
            throw new IllegalStateException(("testing %s at %s takes Faultline's fake provider in the deployment, with the app pointed at it: "
                + "a Compose service running faultline stub (plan --compose)").formatted(risk.pattern(), risk.location()));
        }
    }

    private static List<String> checks(String... extra) {
        var checks = new ArrayList<>(Plan.DATA_CHECKS);
        checks.addAll(List.of(extra));
        checks.add(Plan.FAULTS_FIRED);
        return checks;
    }

    private static boolean isListener(App app, String handler) {
        return app.code().kafkaListeners().stream().anyMatch(l -> l.handler().equals(handler));
    }

    private static Method method(App app, Risk risk) {
        return app.code().method(risk.location())
            .orElseThrow(() -> new IllegalStateException(risk.location() + " is no longer in the code: run ./faultline scan again"));
    }
}
