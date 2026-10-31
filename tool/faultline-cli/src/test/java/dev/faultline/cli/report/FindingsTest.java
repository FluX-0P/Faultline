package dev.faultline.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.plan.TestPlans;
import dev.faultline.cli.report.Finding.Label;
import dev.faultline.cli.run.AttemptResult;
import org.junit.jupiter.api.Test;

/** The plan asks for 2 runs with the fault (both must fail) and 1 baseline without it. */
class FindingsTest {
    private static final Plan PLAN = TestPlans.valid();

    @Test
    void failingInEnoughRunsWithACleanBaselineIsConfirmed() {
        var finding = Findings.of(PLAN, List.of(broken("run-1", 3), broken("run-2", 3), clean("baseline-1", null))).getFirst();

        assertThat(finding.label()).isEqualTo(Label.CONFIRMED);
        assertThat(finding.reason()).isEqualTo("no-duplicates failed in 2 of 2 runs with the fault, and passed in the 1 baseline run without it.");
        assertThat(finding.evidence()).containsExactly("pay-42-299 (2x)");
    }

    @Test
    void failingInTooFewRunsIsOnlySuspected() {
        var finding = Findings.of(PLAN, List.of(broken("run-1", 3), clean("run-2", 3), clean("baseline-1", null))).getFirst();

        assertThat(finding.label()).isEqualTo(Label.SUSPECTED);
        assertThat(finding.reason()).contains("failed in 1 of 2 runs with the fault; confirming it takes 2");
    }

    @Test
    void everyCheckPassingIsSuspected() {
        assertThat(Findings.of(PLAN, List.of(clean("run-1", 3), clean("run-2", 3), clean("baseline-1", null))).getFirst().label())
            .isEqualTo(Label.SUSPECTED);
    }

    @Test
    void aBaselineThatFailsTooMeansTheFaultIsNotTheCause() {
        var finding = Findings.of(PLAN, List.of(broken("run-1", 3), broken("run-2", 3), broken("baseline-1", null))).getFirst();

        assertThat(finding.label()).isEqualTo(Label.NOT_TESTED);
        assertThat(finding.reason()).contains("also in 1 of 1 baseline runs");
    }

    @Test
    void aFaultThatNeverFiredProvesNothing() {
        var finding = Findings.of(PLAN, List.of(broken("run-1", 2), broken("run-2", 0), clean("baseline-1", null))).getFirst();

        assertThat(finding.label()).isEqualTo(Label.NOT_TESTED);
        assertThat(finding.reason()).contains("never fired all 3 times");
    }

    @Test
    void aPlanWithoutARiskHasNoFindings() {
        var p = PLAN;
        var noRisk = new Plan(p.app(), p.journey(), null, p.target(), p.database(), p.kafka(), null, null, null, p.traffic(), p.correlateBy(),
            null, null, p.fault(), p.checks(), null, null, p.confirm());
        assertThat(Findings.of(noRisk, List.of(broken("run-1", 3)))).isEmpty();
    }

    private static AttemptResult broken(String name, Integer fired) {
        return attempt(name, fired, new CheckResult("no-duplicates", false, List.of("pay-42-299 (2x)")));
    }

    private static AttemptResult clean(String name, Integer fired) {
        return attempt(name, fired, new CheckResult("no-duplicates", true, List.of()));
    }

    private static AttemptResult attempt(String name, Integer fired, CheckResult duplicates) {
        String kind = name.startsWith("baseline") ? AttemptResult.BASELINE : AttemptResult.FAULT;
        return new AttemptResult(name, kind, new AttemptResult.Sent(1000, 0, 0, 10_000), new AttemptResult.Drain(1000, 0, true, 3_000),
            null, fired, List.of(new CheckResult("no-lost", true, List.of()), duplicates));
    }
}
