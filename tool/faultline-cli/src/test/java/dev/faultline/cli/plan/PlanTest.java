package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanTest {

    @Test
    void aValidPlanHasNoProblemsAndSurvivesTheRoundTrip(@TempDir Path dir) throws Exception {
        Plan plan = TestPlans.valid();
        assertThat(plan.problems()).isEmpty();

        Path file = dir.resolve("plan.yaml");
        plan.write(file, List.of("a header line"));
        String yaml = Files.readString(file);

        assertThat(Plan.read(file)).isEqualTo(plan);
        assertThat(yaml).startsWith("# a header line\n")
            .contains("correlate_by:").contains("nth_hit: 300")
            .contains("body: |\n      {\"paymentId\": \"pay-${seed}-${seq}\"")   // the body is a block, no escaped quotes
            .doesNotContain("seed: null");
    }

    @Test
    void aTypoInAHandEditIsAnErrorNotIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("plan.yaml");
        TestPlans.valid().write(file, List.of());
        Files.writeString(file, Files.readString(file).replace("nth_hit:", "nth_hits:"));

        assertThatThrownBy(() -> Plan.read(file)).hasMessageContaining("nth_hits");
    }

    @Test
    void reportsWhatIsWrongInWordsYouCanActOn() {
        Plan p = TestPlans.valid();
        Plan broken = new Plan(p.app(), p.journey(), p.risk(),
            new Plan.Target("localhost:8080", null, null, List.of()),
            new Plan.Database("jdbc:mysql://x/y", "demo", "demo", List.of("DROP TABLE payments")),
            p.kafka(), null, null, null,
            new Plan.Traffic(0, 100, null, new Plan.Request("GET", "/payments", "{\"paymentId\": \"pay-${seq}\"}"), null),
            new Plan.CorrelateBy("paymentId", "payments; drop table x", "payment_id"), null, null,
            p.fault(), List.of("no-lost", "no-lies"), null, null, new Plan.Confirm(2, 1, 3));

        assertThat(broken.problems()).contains(
            "target.url must start with http:// or https://",
            "database.url must be a jdbc:postgresql: URL (only Postgres for now)",
            "database.reset: only single TRUNCATE, DELETE FROM and UPDATE statements are allowed, not: DROP TABLE payments",
            "traffic.count must be at least 1",
            "traffic.request.method must be POST, PUT or PATCH",
            "traffic.request.body: 'paymentId' must differ per run: build it from ${seed}",
            "correlate_by.table is not a table name: payments; drop table x",
            "a code fault needs target.jar or target.compose: Faultline has to start the app to attach its agent",
            "confirm.min_failing must be between 1 and confirm.runs",
            "unknown check 'no-lies': use no-lost, no-duplicates, no-unexpected, per-key-order, no-lost-downstream, recovery-within or faults-fired");
    }

    @Test
    void aRunPinsTheSeedAndItsArgumentsWinOverThePlans() {
        Plan p = TestPlans.valid();
        Plan withArgs = new Plan(p.app(), p.journey(), p.risk(), new Plan.Target(p.target().url(), p.target().jar(), null,
            List.of("--payments.dedupe=none", "--server.port=8080")), p.database(), p.kafka(), null, null, null, p.traffic(), p.correlateBy(),
            null, null, p.fault(), p.checks(), null, null, p.confirm());

        Plan run = withArgs.forRun(42, null, 50, List.of("--payments.dedupe=claim-first"), 3, null);

        assertThat(run.traffic().seed()).isEqualTo(42);
        assertThat(run.traffic().count()).isEqualTo(3000);
        assertThat(run.traffic().rate()).isEqualTo(50);
        assertThat(run.target().args()).containsExactly("--payments.dedupe=claim-first", "--server.port=8080");
        assertThat(run.appSettings()).containsEntry("payments.dedupe", "claim-first");
        assertThat(run.confirm()).isEqualTo(new Plan.Confirm(3, 1, 2));
    }

    @Test
    void aBaselineIsThePlanWithoutItsFault() {
        Plan baseline = TestPlans.valid().withoutFault();

        assertThat(baseline.fault()).isNull();
        assertThat(baseline.checks()).containsExactly("no-lost", "no-duplicates", "no-unexpected");
        assertThat(baseline.problems()).isEmpty();
    }

    @Test
    void codeFaultsCanHitAnyMethodBeforeOrAfter() {
        var delay = new Plan.Fault(null, "PaymentService#apply", "before", "delay", 200L, 1, "15s", "worker-1", null);
        assertThat(TestPlans.compose(delay).problems()).isEmpty();

        var bad = new Plan.Fault("code", "PaymentService", "during", "delay", 0L, 1, null, "worker-9", null);
        assertThat(TestPlans.compose(bad).problems()).contains(
            "fault.point must be before-ack or Class#method, such as PaymentService#apply",
            "fault.when must be before or after",
            "fault.nth_hit must be at least 1",
            "a delay needs a duration, such as 15s",
            "fault.target must name the service that gets the agent, one of [api, worker-1, worker-2, worker-3]");
    }

    @Test
    void processFaultsNeedComposeATargetAndATime() {
        var pause = new Plan.Fault("process", null, null, "pause", null, 1, "12s", "worker-1", "8s");
        assertThat(TestPlans.compose(pause).problems()).isEmpty();
        assertThat(TestPlans.valid().problems()).isEmpty();

        var onAJar = new Plan(TestPlans.valid().app(), null, null, TestPlans.valid().target(), TestPlans.valid().database(), TestPlans.valid().kafka(),
            null, null, null, TestPlans.valid().traffic(), TestPlans.valid().correlateBy(), null, null,
            new Plan.Fault("process", null, null, "freeze", null, 1, null, "worker-1", null), List.of("no-lost"), null, null, null);
        assertThat(onAJar.problems()).contains(
            "fault.action must be one of [kill, pause, restart] for a process fault",
            "a process fault needs target.compose: it stops and starts Docker services",
            "fault.at must say when, after the traffic starts, such as 10s",
            "fault.duration must say how long the service stays down, such as 15s");
    }

    @Test
    void relayOrderRulesAndTheNewFaultLayersAreChecked() {
        var corrupt = new Plan.Fault("messaging", null, null, "corrupt", 300L, 1, null, null, null);
        assertThat(TestPlans.ordered(corrupt).problems()).isEmpty();
        assertThat(TestPlans.compose(corrupt).problems()).contains("a messaging fault needs a relay: from and to topics");

        var twice = new Plan.Fault("traffic", null, null, "duplicate", 50L, 20, null, null, null);
        assertThat(TestPlans.compose(twice).problems()).isEmpty();
        assertThat(TestPlans.compose(new Plan.Fault("traffic", null, null, "drop", 0L, 1, null, null, null)).problems())
            .contains("fault.action must be one of [duplicate] for a traffic fault", "fault.nth_hit must be at least 1");

        Plan p = TestPlans.ordered(corrupt);
        Plan bad = new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), p.kafka(), new Plan.Relay("t", "t"), null, null,
            p.traffic(), p.correlateBy(), null, null, p.fault(), p.checks(), List.of(new Plan.Rule("r", "delete from payments")), null, p.confirm());
        assertThat(bad.problems()).contains(
            "relay needs two different topics, from and to",
            "check per-key-order needs order: key and by",
            "rule r: sql must be one SELECT (or WITH) statement that returns the offending rows");
    }

    @Test
    void aBarrierNeedsAnUntilPointAndPartnersThatAreOtherServices() {
        var barrier = new Plan.Fault(null, "MerchantBalance#read", "after", "barrier", 5L, 3, "3s", "worker-1", null,
            "MerchantBalance#write", List.of("worker-2", "worker-3"), null);
        assertThat(TestPlans.compose(barrier).problems()).isEmpty();

        var bad = new Plan.Fault(null, "MerchantBalance#read", "after", "barrier", 5L, 3, null, "worker-1", null,
            "write", List.of("worker-1"), null);
        assertThat(TestPlans.compose(bad).problems()).contains(
            "a barrier needs until: the Class#method another worker must return from before it lets go",
            "a barrier needs a duration: the longest it holds, such as 3s",
            "fault.partners must be other services of target.compose.services, not worker-1");
        assertThat(TestPlans.compose(new Plan.Fault(null, "A#b", null, "crash", 1L, 1, null, "worker-1", null, "A#c", null, null)).problems())
            .contains("fault.until and fault.partners are only for a barrier");
    }

    @Test
    void networkAndServiceFaultsNeedTheirHelpersAddress() {
        var slowDb = new Plan.Fault("network", null, null, "latency", null, 1, "10s", "worker-1-db", "5s", null, null, "3s");
        var outage = new Plan.Fault("service", null, null, "outage", null, 1, "6s", null, "5s");
        var late = new Plan.Fault("service", null, null, "late-reply", 100L, 5, "3s", null, null);
        assertThat(TestPlans.helped(slowDb, "recovery-within").problems()).isEmpty();
        assertThat(TestPlans.helped(outage, "recovery-within", "no-lost-downstream").problems()).isEmpty();
        assertThat(TestPlans.helped(late).problems()).isEmpty();

        assertThat(TestPlans.compose(slowDb).problems()).contains("a network fault needs toxiproxy: the address of its API, such as http://localhost:8474");
        assertThat(TestPlans.compose(outage).problems()).contains("a service fault needs stub: the address of the fake payment provider, such as http://localhost:9090");
        assertThat(TestPlans.helped(new Plan.Fault("network", null, null, "latency", null, 1, null, null, null)).problems()).contains(
            "fault.target must name the Toxiproxy proxy to break, such as worker-1-db",
            "a latency fault needs latency, such as 3s",
            "fault.at must say when, after the traffic starts, such as 10s",
            "fault.duration must say how long it lasts, such as 15s");
        assertThat(TestPlans.helped(new Plan.Fault("service", null, null, "late-reply", 0L, 1, null, null, null)).problems()).contains(
            "fault.nth_hit must be at least 1", "fault.duration must say how late the stub replies, such as 3s");
    }

    @Test
    void aWarmUpIsKeptForTheRunAndCannotBeNegative() {
        Plan p = TestPlans.valid();
        var t = p.traffic();
        Plan warm = new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), p.kafka(), null, null, null,
            new Plan.Traffic(t.count(), t.rate(), null, t.request(), null, 30), p.correlateBy(), null, null, p.fault(), p.checks(), null, null, p.confirm());

        assertThat(warm.forRun(42, null, null, List.of(), null, null).traffic().warmupCount()).isEqualTo(30);
        assertThat(TestPlans.valid().traffic().warmupCount()).isZero();
        Plan negative = new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), p.kafka(), null, null, null,
            new Plan.Traffic(t.count(), t.rate(), null, t.request(), null, -1), p.correlateBy(), null, null, p.fault(), p.checks(), null, null, p.confirm());
        assertThat(negative.problems()).contains("traffic.warmup cannot be negative");
    }

    @Test
    void authNamesItsSecretByEnvironmentVariableAndSurvivesTheRun(@TempDir Path dir) throws Exception {
        Plan p = TestPlans.valid();
        var t = p.target();
        Plan withAuth = new Plan(p.app(), p.journey(), p.risk(), new Plan.Target(t.url(), t.jar(), null, t.args(), new Plan.Auth("Authorization", "Bearer ${env:SHOP_TOKEN}")),
            new Plan.Database(p.database().url(), p.database().user(), p.database().password(), p.database().reset(), "faultline/seed.sql"),
            p.kafka(), null, null, null, p.traffic(), p.correlateBy(), null, null, p.fault(), p.checks(), null, null, p.confirm());

        assertThat(withAuth.problems()).isEmpty();
        Path file = dir.resolve("plan.yaml");
        withAuth.write(file, List.of());
        assertThat(Files.readString(file)).contains("value: \"Bearer ${env:SHOP_TOKEN}\"").contains("seed_file: faultline/seed.sql");
        assertThat(Plan.read(file).forRun(42, null, null, List.of("--a=b"), null, null).target().auth()).isEqualTo(withAuth.target().auth());

        Plan secretInline = new Plan(p.app(), p.journey(), p.risk(), new Plan.Target(t.url(), t.jar(), null, t.args(), new Plan.Auth("Bad Header", "Bearer ${SHOP_TOKEN}")),
            new Plan.Database(p.database().url(), p.database().user(), p.database().password(), p.database().reset(), "seed.txt"),
            p.kafka(), null, null, null, p.traffic(), p.correlateBy(), null, null, p.fault(), p.checks(), null, null, p.confirm());
        assertThat(secretInline.problems()).contains("target.auth.header must be a header name, such as Authorization",
            "target.auth.value: the only placeholder is ${env:NAME}, an environment variable that holds the secret",
            "database.seed_file must be a .sql file");
    }

    @Test
    void recoveryWithinNeedsAFaultThatEndsAndLeavesTheBaseline() {
        var late = new Plan.Fault("service", null, null, "late-reply", 100L, 5, "3s", null, null);
        assertThat(TestPlans.helped(late, "recovery-within").problems())
            .contains("check recovery-within needs recovery_within (such as 30s) and a fault that lasts from at for a duration (process, network or a service outage)");

        var outage = new Plan.Fault("service", null, null, "outage", null, 1, "6s", null, "5s");
        Plan baseline = TestPlans.helped(outage, "recovery-within").withoutFault();
        assertThat(baseline.checks()).doesNotContain("recovery-within", "faults-fired");
        assertThat(baseline.problems()).isEmpty();
    }
}
