package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A plan for a journey through several services: a database per service, each named, and what each part runs in. */
class SeveralDatabasesTest {
    @TempDir
    Path dir;

    private static final Plan.Database ORDERS = new Plan.Database("dborder", "jdbc:postgresql://localhost:15432/dborder", "dbuser", "dbpass",
        List.of("TRUNCATE ordertable"), null);
    private static final Plan.Database SHIPPING = new Plan.Database("dbshipping", "jdbc:postgresql://localhost:15432/dbshipping", "dbuser", "dbpass",
        List.of("TRUNCATE shipment"), null);

    private static Plan with(Plan.Database database, List<Plan.Database> databases, Plan.CorrelateBy correlateBy, List<Plan.Rule> rules,
                             Plan.Request request) {
        var p = TestPlans.valid();
        var t = p.traffic();
        return new Plan(p.app(), p.journey(), p.risk(), p.target(), database, databases, p.kafka(), null, null, null,
            new Plan.Traffic(t.count(), t.rate(), null, request, null), correlateBy, null, null, p.fault(), p.checks(), rules, null, p.confirm());
    }

    private static Plan.Request request() {
        return TestPlans.valid().traffic().request();
    }

    @Test
    void aJourneyThroughServicesHasADatabaseForEach() throws Exception {
        var plan = with(null, List.of(ORDERS, SHIPPING), new Plan.CorrelateBy("paymentId", null, "dbshipping", "shipment", "street"),
            List.of(new Plan.Rule("one-line-per-order", "select 1 from ordertable where false", null, null, "dborder")), request());

        assertThat(plan.problems()).isEmpty();
        assertThat(plan.allDatabases()).extracting(Plan.Database::name).containsExactly("dborder", "dbshipping");
        assertThat(plan.effectsDatabase()).isSameAs(SHIPPING);
        assertThat(plan.databaseNamed(null)).isSameAs(ORDERS);

        // written and read back as it is
        Path file = dir.resolve("plan.yaml");
        plan.write(file, List.of());
        assertThat(Files.readString(file)).contains("databases:", "- name: dborder", "  database: dbshipping");
        assertThat(Plan.read(file)).isEqualTo(plan);
    }

    @Test
    void everyPartNamesADatabaseThePlanHas() {
        var unknown = with(null, List.of(ORDERS, SHIPPING), new Plan.CorrelateBy("paymentId", null, "dbinvoice", "invoice", "id"),
            List.of(new Plan.Rule("r", "select 1", null, null, "dbnone")), request());
        assertThat(unknown.problems()).contains("correlate_by.database must name one of databases: dborder, dbshipping",
            "rule r: database must name one of databases: dborder, dbshipping");

        var twice = with(null, List.of(ORDERS, ORDERS), new Plan.CorrelateBy("paymentId", "shipment", "street"), null, request());
        assertThat(twice.problems()).contains("every database of databases needs its own name");

        var both = with(TestPlans.valid().database(), List.of(ORDERS), TestPlans.valid().correlateBy(), null, request());
        assertThat(both.problems()).contains("use database (one) or databases (several), not both");

        var one = with(TestPlans.valid().database(), null, new Plan.CorrelateBy("paymentId", null, "dborder", "payments", "payment_id"), null, request());
        assertThat(one.problems()).contains("correlate_by.database is only for a plan with databases");
    }

    @Test
    void aBrokenDatabaseOfSeveralIsNamedByItsName() {
        var bad = new Plan.Database("dbshipping", "jdbc:mysql://x/y", null, null, List.of("DROP TABLE shipment"), null);
        var plan = with(null, List.of(ORDERS, bad), new Plan.CorrelateBy("paymentId", "shipment", "street"), null, request());

        assertThat(plan.problems()).contains("databases.dbshipping.url must be a jdbc:postgresql: URL (only Postgres for now)",
            "databases.dbshipping.user is missing",
            "databases.dbshipping.reset: only single TRUNCATE, DELETE FROM and UPDATE statements are allowed, not: DROP TABLE shipment");
    }

    @Test
    void aFormIsTheOnlyOtherContentType() {
        var r = request();
        var form = with(TestPlans.valid().database(), null, TestPlans.valid().correlateBy(), null,
            new Plan.Request(r.method(), r.path(), Plan.Request.FORM, r.body()));
        var xml = with(TestPlans.valid().database(), null, TestPlans.valid().correlateBy(), null,
            new Plan.Request(r.method(), r.path(), "application/xml", r.body()));

        assertThat(form.problems()).isEmpty();
        assertThat(form.traffic().request().form()).isTrue();
        assertThat(xml.problems()).contains("traffic.request.content_type must be application/json or application/x-www-form-urlencoded");
    }

    @Test
    void aDraftsPathLosesTheSlipsAroundABareSlash() {
        assertThat(new PlanDraft.Request("POST", "/,", "{}").path()).isEqualTo("/");
        assertThat(new PlanDraft.Request("POST", "", "{}").path()).isEqualTo("/");
        assertThat(new PlanDraft.Request("POST", " /orders? ", "{}").path()).isEqualTo("/orders");
        assertThat(new PlanDraft.Request("POST", "/payments", "{}").path()).isEqualTo("/payments");
    }
}
