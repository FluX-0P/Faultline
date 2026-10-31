package dev.faultline.cli.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import dev.faultline.cli.plan.Durations;
import dev.faultline.cli.plan.Plan;
import org.junit.jupiter.api.Test;

/** Guards the benchmark the repo ships: every plan in it must read and be valid with its bug switched on or off. */
class BenchmarkFileTest {
    private static final Path BENCHMARK = Path.of("../../benchmark/bugs.yaml");

    @Test
    void everyShippedPlanIsValidWithItsBugOnAndOff() throws Exception {
        var bench = BenchmarkFile.read(BENCHMARK);
        assertThat(bench.bugs()).extracting(BenchmarkFile.Bug::id).contains("B1", "B10");

        for (var bug : bench.bugs()) {
            Plan plan = Plan.read(BENCHMARK.resolveSibling(bug.plan()));
            assertThat(plan.forRun(42, null, null, bench.args(bug), null, null).problems()).as(bug.id() + " on").isEmpty();
            assertThat(plan.forRun(42, null, null, bench.args(null), null, null).problems()).as(bug.id() + " off").isEmpty();
        }
    }

    @Test
    void aBugsSettingsWinOverTheBugFreeOnes() throws Exception {
        var bench = BenchmarkFile.read(BENCHMARK);
        var b1 = bench.bugs().stream().filter(b -> b.id().equals("B1")).findFirst().orElseThrow();

        assertThat(bench.args(b1)).containsExactly("--payments.dedupe=none");
        assertThat(bench.args(null)).containsExactly("--payments.dedupe=claim-first");
    }

    @Test
    void durationsReadTheWayPlansWriteThem() {
        assertThat(Durations.parse("15s").toMillis()).isEqualTo(15_000);
        assertThat(Durations.parse("500ms").toMillis()).isEqualTo(500);
        assertThat(Durations.parse("2m").toSeconds()).isEqualTo(120);
    }
}
