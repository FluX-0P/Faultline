package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.app.Environment;
import dev.faultline.cli.app.TestDeployments;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.TestApps;
import dev.faultline.cli.understand.AppMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanCommandTest {
    private static final Risk RISK = new Risk("R1", "P1", "Save, then acknowledge the message", "PaymentListener#onMessage",
        "src/main/java/pay/PaymentListener.java", 8, "high", "no duplicate check");
    private static final AppMap MAP = new AppMap("payments", "m", "Payments.", List.of(), List.of(), List.of(), List.of(),
        List.of(new AppMap.Journey("make-payment", "pay", List.of(new AppMap.Step("PaymentController#pay", "accept")))));
    private static final Deployment JAR = Deployment.jar("http://localhost:8081");

    @Test
    void readsTheFactsFromTheAppNotTheLlm(@TempDir Path dir) throws Exception {
        var app = App.read(TestApps.payments(dir));
        var facts = PlanCommand.facts(app, JAR, PlanCommand.aim(app, MAP, RISK, JAR));

        assertThat(facts.url()).isEqualTo("http://localhost:8081");
        assertThat(facts.dbUrl()).isEqualTo("jdbc:postgresql://localhost:5432/pay");
        assertThat(facts.topic()).isEqualTo("payments.in");
        assertThat(facts.group()).isEqualTo("from-config");   // the listener names no group, so the config's
        assertThat(facts.jar()).endsWith("target/payments-1.2.0.jar");   // not built yet: the name Maven will give it
    }

    @Test
    void aGoodDraftMakesAValidPlanWithTheTemplatesFaultAndTheDraftedRules(@TempDir Path dir) throws Exception {
        var app = App.read(TestApps.payments(dir));
        var aim = PlanCommand.aim(app, MAP, RISK, JAR);
        var facts = PlanCommand.facts(app, JAR, aim);
        var draft = draft("make-payment", "/api/payments", List.of("TRUNCATE payments;"),
            List.of(new PlanDraft.Rule("one-row-per-payment", "a payment is applied once", "select payment_id from payments group by payment_id having count(*) > 1;")));

        assertThat(PlanCommand.problems(draft, app, MAP, aim, facts, Map.of())).isEmpty();
        Plan plan = PlanCommand.build(draft, app.name(), aim, facts, Map.of("payments.mode", "fast"));
        assertThat(plan.database().reset()).containsExactly("TRUNCATE payments");
        assertThat(plan.fault()).isEqualTo(new Plan.Fault(null, "before-ack", null, "crash", 150L, 2, null, null, null));
        assertThat(plan.rules()).containsExactly(new Plan.Rule("one-row-per-payment",
            "select payment_id from payments group by payment_id having count(*) > 1", "a payment is applied once", true));
        assertThat(plan.target().args()).containsExactly("--payments.mode=fast");
        assertThat(plan.confirm()).isEqualTo(new Plan.Confirm(2, 1, 2));
    }

    @Test
    void catchesAnInventedEndpointAndAResetThatLeavesRowsBehind(@TempDir Path dir) throws Exception {
        var app = App.read(TestApps.payments(dir));
        var aim = PlanCommand.aim(app, MAP, RISK, JAR);
        var draft = draft("checkout", "/payments", List.of("UPDATE accounts SET balance = 0"), List.of());

        assertThat(PlanCommand.problems(draft, app, MAP, aim, PlanCommand.facts(app, JAR, aim), Map.of())).contains(
            "journey 'checkout' is not in the app map (journeys: make-payment)",
            "POST /payments is not an endpoint in the code (endpoints: POST /api/payments)",
            "reset does not empty payments, which the journey's code inserts into: rows left by one run would break the next");
    }

    @Test
    void aDraftedRuleMayOnlyUseTablesThatExistAndANameOfItsOwn() {
        var schema = Map.<String, Set<String>>of("payments", new TreeSet<>(Set.of("id", "amount")), "merchant", new TreeSet<>(Set.of("balance")));
        var rules = List.of(
            new PlanDraft.Rule("Balance_OK", "x", "select 1 from merchant"),
            new PlanDraft.Rule("no-lost", "x", "select 1 from payments"),
            new PlanDraft.Rule("ledger-matches", "x", "select * from ledger l join payments p on p.id = l.id"),
            new PlanDraft.Rule("with-a-cte", "x", "with paid as (select sum(amount) s from payments) select * from merchant, paid where balance <> s"));

        assertThat(PlanCommand.ruleProblems(rules, schema)).containsExactly(
            "rule id 'Balance_OK' must be kebab-case, such as stock-never-negative",
            "rule id no-lost is the name of one of Faultline's checks: give the rule a name of its own",
            "rule ledger-matches: ledger is not a table (tables: merchant, payments)");
    }

    @Test
    void aDraftedRuleMayOnlyNameColumnsTheMigrationsCreate() throws Exception {
        var schema = PlanCommand.schema(App.read(TestDeployments.DEMO_APP), TestDeployments.demo());
        assertThat(schema.get("merchant")).containsExactly("balance_minor", "merchant_id");
        assertThat(schema.get("payments")).containsExactly("account_id", "amount_minor", "created_at", "id", "payment_id");
        assertThat(schema.get("stub.charges")).contains("payment_id", "idempotency_key");

        var wrong = new PlanDraft.Rule("shop-adds-up", "x",
            "SELECT m.merchant_id FROM merchant m WHERE m.merchant_balance <> COALESCE((SELECT sum(p.amount_minor) FROM payments p), 0)");
        var right = new PlanDraft.Rule("charged-once", "x",
            "select c.payment_id from stub.charges c join payments on payments.payment_id = c.payment_id group by c.payment_id having count(*) > 1");

        assertThat(PlanCommand.ruleProblems(List.of(wrong, right), schema))
            .containsExactly("rule shop-adds-up: merchant has no column merchant_balance (its columns: balance_minor, merchant_id)");
    }

    @Test
    void theResetMustSetBackWhatTheJourneyUpdatesAndEmptyTheProvidersLedger() throws Exception {
        var app = App.read(TestDeployments.DEMO_APP);
        var deployment = TestDeployments.demo();
        var aim = PlanCommand.aim(app, FaultTemplateTest.MAP, new Risk("R1", "P4", "t", "MerchantBalance#credit", "f", 1, "high", "w"), deployment);
        var facts = PlanCommand.facts(app, deployment, aim);
        var body = "{\"paymentId\": \"pay-${seed}-${seq}\", \"accountId\": \"acct-${int:1:100}\", \"amountMinor\": ${int:100:5000}}";
        var draft = new PlanDraft("make-payment", new PlanDraft.Request("POST", "/payments", body), "paymentId",
            new PlanDraft.Effect("payments", "payment_id"),
            List.of("TRUNCATE payments", "TRUNCATE processed_events", "TRUNCATE outbox", "UPDATE accounts SET balance_minor = 1000000"), List.of(), "w");

        assertThat(PlanCommand.problems(draft, app, FaultTemplateTest.MAP, aim, facts, Map.of()))
            .containsExactly("reset does not set merchant back, which the journey's code updates: UPDATE it to the values the migrations set");
        var wipesAccounts = new PlanDraft(draft.journey(), draft.request(), draft.correlateBy(), draft.effect(),
            List.of("TRUNCATE payments", "TRUNCATE processed_events", "TRUNCATE outbox", "TRUNCATE accounts CASCADE", "UPDATE merchant SET balance_minor = 0"),
            List.of(), "w");
        assertThat(PlanCommand.problems(wipesAccounts, app, FaultTemplateTest.MAP, aim, facts, Map.of()))
            .containsExactly("reset empties accounts, which the migrations fill: UPDATE its rows back to the migrations' values instead");
        var plan = PlanCommand.build(draft, app.name(), aim, facts, Map.of());
        assertThat(plan.database().reset()).last().isEqualTo("TRUNCATE stub.charges");
        assertThat(plan.target().compose()).isEqualTo(deployment.compose());
        assertThat(plan.target().jar()).isNull();
    }

    @Test
    void withDoctorsEnvironmentThePlanJudgesTheAppAsItRunsAndReachesItFromHere(@TempDir Path dir) throws Exception {
        var environment = new Environment("payments", new Environment.Compose("compose.yml", null, "payments", null),
            List.of(new Environment.Service("worker", "", Map.of("SPRING_DATASOURCE_URL", "jdbc:postgresql://pg:5432/pay",
                "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"), List.of(), true)),
            new Environment.Kafka("kafka", List.of("kafka:9092"), "localhost:39092", "a listener Faultline's override adds"),
            List.of(new Environment.Database("pg", "pg:5432", "pay", "localhost:15432", "pay", "secret", List.of("worker"), List.of(
                new Environment.Table("payments", List.of(new Environment.Column("payment_id", "text"), new Environment.Column("amount", "bigint")), 0),
                new Environment.Table("accounts", List.of(new Environment.Column("account_id", "text")), 5)))),
            List.of(), List.of(), "2026-11-01T00:00:00Z");
        var app = App.read(TestApps.payments(dir), environment);
        var aim = PlanCommand.aim(app, MAP, RISK, JAR);
        var facts = PlanCommand.facts(app, JAR, aim);

        assertThat(app.config().get("spring.datasource.url")).contains("jdbc:postgresql://pg:5432/pay");
        assertThat(facts.dbUrl()).isEqualTo("jdbc:postgresql://localhost:15432/pay");
        assertThat(facts.bootstrap()).isEqualTo("localhost:39092");
        // the live tables, even though the migrations create only payments
        assertThat(PlanCommand.schema(app, JAR)).containsOnlyKeys("payments", "accounts");
        assertThat(PlanCommand.seededTables(app)).containsExactly("accounts");
        assertThat(PlanCommand.prompt(app, MAP, aim, JAR, facts)).contains(Environment.SCHEMA_TITLE)
            .contains("table payments (payment_id text, amount bigint) -- 0 rows after the app started");

        // without one, the prompt is what it was, so earlier answers stay cached
        var files = App.read(dir);
        var filesAim = PlanCommand.aim(files, MAP, RISK, JAR);
        assertThat(PlanCommand.prompt(files, MAP, filesAim, JAR, PlanCommand.facts(files, JAR, filesAim))).doesNotContain(Environment.SCHEMA_TITLE);
    }

    private static PlanDraft draft(String journey, String path, List<String> reset, List<PlanDraft.Rule> rules) {
        return new PlanDraft(journey, new PlanDraft.Request("POST", path, "{\"paymentId\":\"p-${seed}-${seq}\",\"amount\":${int:1:9}}"),
            "paymentId", new PlanDraft.Effect("payments", "payment_id"), reset, rules, "reaches the listener");
    }
}
