package dev.faultline.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import dev.faultline.cli.Json;
import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.plan.TestPlans;
import dev.faultline.cli.run.AttemptResult;
import dev.faultline.cli.run.RunInfo;
import dev.faultline.cli.run.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    @Test
    void writesAPageThatLeadsWithTheConfirmedBugAndShowsEveryRun(@TempDir Path runDir) throws Exception {
        Plan plan = TestPlans.valid().forRun(42, null, null, List.of(), null, null);
        var broken = List.of(new CheckResult("no-lost", true, List.of()), new CheckResult("no-duplicates", false, List.of("pay-42-299 (2x)")));
        var clean = List.of(new CheckResult("no-lost", true, List.of()), new CheckResult("no-duplicates", true, List.of()));
        var sent = new AttemptResult.Sent(898, 2027, 75, 30_000);
        var drain = new AttemptResult.Drain(901, 0, true, 6_100);
        var attempts = List.of(new AttemptResult("run-1", AttemptResult.FAULT, sent, drain, 3, 3, broken),
            new AttemptResult("run-2", AttemptResult.FAULT, sent, drain, 3, 3, broken),
            new AttemptResult("baseline-1", AttemptResult.BASELINE, sent, drain, 0, null, clean));
        var result = new RunResult("FAIL", attempts, Findings.of(plan, attempts), "./faultline run runs/r1/plan.yaml");
        plan.write(runDir.resolve("plan.yaml"), List.of());
        Json.writePretty(runDir.resolve("run.json"), new RunInfo(".faultline/demo-app/plan.yaml", 42, 3000, 100, 2, 1, Instant.now()));
        Json.writePretty(runDir.resolve("result.json"), result);

        String html = Files.readString(ReportWriter.write(runDir));

        assertThat(html).contains("<title>Faultline report: demo-app</title>")
            .contains("1 confirmed bug")
            .contains("2 runs with the fault, 1 baseline")
            .contains("<span class=\"chip confirmed\">confirmed</span>")
            .contains("PaymentListener#onMessage")
            .contains("<td class=\"mono\">baseline-1</td>")
            .contains("<span class=\"chip fail\">no-duplicates 1</span>")
            .contains("<code>pay-42-299 (2x)</code>")
            .contains("./faultline run runs/r1/plan.yaml")
            .contains("&quot;paymentId&quot;");   // the plan is shown escaped, not as markup
    }

    @Test
    void escapesEverythingItShows() {
        assertThat(ReportWriter.esc("<script>alert(\"x\") & more</script>"))
            .isEqualTo("&lt;script&gt;alert(&quot;x&quot;) &amp; more&lt;/script&gt;");
    }
}
