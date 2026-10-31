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
import dev.faultline.cli.run.RunInfo;
import dev.faultline.cli.run.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    @Test
    void writesAPageThatLeadsWithTheConfirmedBug(@TempDir Path runDir) throws Exception {
        Plan plan = TestPlans.valid().forRun(42, null, null, List.of());
        var checks = List.of(new CheckResult("no-lost", true, List.of()), new CheckResult("no-duplicates", false, List.of("pay-42-299 (2x)")));
        var result = new RunResult("FAIL", new RunResult.Sent(898, 2027, 75, 30_000), new RunResult.Drain(901, 0, true, 6_100),
            3, 3, checks, Findings.of(plan, checks, 3), "./faultline run runs/r1/plan.yaml");
        plan.write(runDir.resolve("plan.yaml"), List.of());
        Json.writePretty(runDir.resolve("run.json"), new RunInfo(".faultline/demo-app/plan.yaml", 42, 3000, 100, Instant.now()));
        Json.writePretty(runDir.resolve("result.json"), result);

        String html = Files.readString(ReportWriter.write(runDir));

        assertThat(html).contains("<title>Faultline report: demo-app</title>")
            .contains("1 confirmed bug")
            .contains("<span class=\"chip confirmed\">confirmed</span>")
            .contains("PaymentListener#onMessage")
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
