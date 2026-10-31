package dev.faultline.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.plan.TestPlans;
import dev.faultline.cli.report.Finding.Label;
import org.junit.jupiter.api.Test;

class FindingsTest {
    private static final Plan PLAN = TestPlans.valid();
    private static final CheckResult LOST_OK = new CheckResult("no-lost", true, List.of());
    private static final CheckResult DUPLICATES = new CheckResult("no-duplicates", false, List.of("pay-42-299 (2x)"));
    private static final CheckResult NO_DUPLICATES = new CheckResult("no-duplicates", true, List.of());

    @Test
    void aFailedCheckWithTheFaultFiringIsConfirmed() {
        var finding = Findings.of(PLAN, List.of(LOST_OK, DUPLICATES), 3).getFirst();

        assertThat(finding.label()).isEqualTo(Label.CONFIRMED);
        assertThat(finding.reason()).isEqualTo("no-duplicates (1) failed after 3 planned crashes at before-ack.");
        assertThat(finding.evidence()).containsExactly("pay-42-299 (2x)");
        assertThat(finding.location()).isEqualTo("PaymentListener#onMessage");
    }

    @Test
    void everyCheckPassingWithTheFaultFiringIsOnlySuspected() {
        assertThat(Findings.of(PLAN, List.of(LOST_OK, NO_DUPLICATES), 3).getFirst().label()).isEqualTo(Label.SUSPECTED);
    }

    @Test
    void aFaultThatDidNotFireProvesNothingEitherWay() {
        var finding = Findings.of(PLAN, List.of(LOST_OK, DUPLICATES), 2).getFirst();

        assertThat(finding.label()).isEqualTo(Label.NOT_TESTED);
        assertThat(finding.reason()).contains("fired 2 of 3 times");
        assertThat(finding.evidence()).isEmpty();
    }

    @Test
    void aPlanWithoutARiskHasNoFindings() {
        var p = PLAN;
        var noRisk = new Plan(p.app(), p.journey(), null, p.target(), p.database(), p.kafka(), p.traffic(), p.correlateBy(), p.fault(), p.checks());
        assertThat(Findings.of(noRisk, List.of(DUPLICATES), 3)).isEmpty();
    }
}
