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
            new Plan.Target("localhost:8080", null, List.of()),
            new Plan.Database("jdbc:mysql://x/y", "demo", "demo", List.of("DROP TABLE payments")),
            p.kafka(),
            new Plan.Traffic(0, 100, null, new Plan.Request("GET", "/payments", "{\"paymentId\": \"pay-${seq}\"}")),
            new Plan.CorrelateBy("paymentId", "payments; drop table x", "payment_id"),
            p.fault(), List.of("no-lost", "no-lies"));

        assertThat(broken.problems()).contains(
            "target.url must start with http:// or https://",
            "database.url must be a jdbc:postgresql: URL (only Postgres for now)",
            "database.reset: only single TRUNCATE, DELETE FROM and UPDATE statements are allowed, not: DROP TABLE payments",
            "traffic.count must be at least 1",
            "traffic.request.method must be POST, PUT or PATCH",
            "traffic.request.body: 'paymentId' must differ per run: build it from ${seed}",
            "correlate_by.table is not a table name: payments; drop table x",
            "a fault needs target.jar: Faultline has to start the app to attach its agent",
            "unknown check 'no-lies': use no-lost, no-duplicates, no-unexpected or faults-fired");
    }

    @Test
    void aRunPinsTheSeedAndAddsArguments() {
        Plan run = TestPlans.valid().forRun(42, null, 50, List.of("--demo.fixes.dedupe=true"));

        assertThat(run.traffic().seed()).isEqualTo(42);
        assertThat(run.traffic().count()).isEqualTo(3000);
        assertThat(run.traffic().rate()).isEqualTo(50);
        assertThat(run.target().args()).containsExactly("--demo.fixes.dedupe=true");
    }
}
