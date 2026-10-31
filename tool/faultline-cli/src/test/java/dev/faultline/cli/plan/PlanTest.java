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
            p.kafka(),
            new Plan.Traffic(0, 100, null, new Plan.Request("GET", "/payments", "{\"paymentId\": \"pay-${seq}\"}")),
            new Plan.CorrelateBy("paymentId", "payments; drop table x", "payment_id"),
            p.fault(), List.of("no-lost", "no-lies"), new Plan.Confirm(2, 1, 3));

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
            "unknown check 'no-lies': use no-lost, no-duplicates, no-unexpected or faults-fired");
    }

    @Test
    void aRunPinsTheSeedAndItsArgumentsWinOverThePlans() {
        Plan p = TestPlans.valid();
        Plan withArgs = new Plan(p.app(), p.journey(), p.risk(), new Plan.Target(p.target().url(), p.target().jar(), null,
            List.of("--payments.dedupe=none", "--server.port=8080")), p.database(), p.kafka(), p.traffic(), p.correlateBy(), p.fault(), p.checks(), p.confirm());

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
            TestPlans.valid().traffic(), TestPlans.valid().correlateBy(), new Plan.Fault("process", null, null, "freeze", null, 1, null, "worker-1", null),
            List.of("no-lost"), null);
        assertThat(onAJar.problems()).contains(
            "fault.action must be one of [kill, pause, restart] for a process fault",
            "a process fault needs target.compose: it stops and starts Docker services",
            "fault.at must say when, after the traffic starts, such as 10s",
            "fault.duration must say how long the service stays down, such as 15s");
    }
}
