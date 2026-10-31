package dev.faultline.cli.plan;

import java.util.ArrayList;
import java.util.List;

import dev.faultline.cli.scan.Risks.Risk;

/** The demo app's B1 plan, as faultline plan draws it up, for tests. */
public final class TestPlans {
    public static final String BODY =
        "{\"paymentId\": \"pay-${seed}-${seq}\", \"accountId\": \"acct-${int:1:100}\", \"amountMinor\": ${int:100:5000}}\n";
    public static final Plan.Compose COMPOSE = new Plan.Compose("deploy/docker-compose.yml", "demo",
        List.of("api", "worker-1", "worker-2", "worker-3"), 3);

    private TestPlans() {}

    public static Plan valid() {
        return new Plan("demo-app", "make-payment",
            new Risk("R1", "P1", "Save, then acknowledge the message", "PaymentListener#onMessage", "src/PaymentListener.java", 18, "high", "no check"),
            new Plan.Target("http://localhost:8080", "demo-app/target/demo-app.jar", null, List.of()),
            new Plan.Database("jdbc:postgresql://localhost:5432/demo", "demo", "demo",
                List.of("TRUNCATE TABLE payments", "UPDATE accounts SET balance_minor = 1000000")),
            new Plan.Kafka("localhost:9092", "payments.requested", "payment-worker"),
            null,
            new Plan.Traffic(3000, 100, null, new Plan.Request("POST", "/payments", BODY), null),
            new Plan.CorrelateBy("paymentId", "payments", "payment_id"),
            null,
            new Plan.Fault(null, "before-ack", null, "crash", 300L, 3, null, null, null),
            List.of("no-lost", "no-duplicates", "no-unexpected", "faults-fired"),
            null,
            new Plan.Confirm(2, 1, 2));
    }

    /** The same plan run with Docker Compose, with this fault on worker-1. */
    public static Plan compose(Plan.Fault fault) {
        Plan p = valid();
        return new Plan(p.app(), p.journey(), p.risk(), new Plan.Target(p.target().url(), null, COMPOSE, List.of()), p.database(), p.kafka(),
            p.relay(), p.traffic(), p.correlateBy(), p.order(), fault, p.checks(), p.rules(), p.confirm());
    }

    /** The B9-style plan: a relay, per-key order and an app rule, with this fault. */
    public static Plan ordered(Plan.Fault fault) {
        Plan p = compose(fault);
        var checks = new ArrayList<>(p.checks());
        checks.add("per-key-order");
        return new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), p.kafka(), new Plan.Relay("payments.raw", "payments.requested"),
            p.traffic(), p.correlateBy(), new Plan.Order("accountId", "id"), fault, checks,
            List.of(new Plan.Rule("one-row-per-payment", "select payment_id from payments group by payment_id having count(*) > 1")), p.confirm());
    }
}
