package dev.faultline.cli.report;

import java.util.List;
import java.util.stream.Collectors;

import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.report.Finding.Label;
import dev.faultline.cli.run.AttemptResult;

/**
 * Labels the plan's risk from all its runs. Confirmed needs three things: the fault fired as planned, a data check
 * (no-lost, no-duplicates, no-unexpected) failed in at least confirm.min_failing of those runs, and every baseline run
 * without the fault passed. A flagged risk the runs could not break stays suspected.
 */
public final class Findings {
    static final int MAX_EVIDENCE = 50;

    private Findings() {}

    public static List<Finding> of(Plan plan, List<AttemptResult> attempts) {
        var risk = plan.risk();
        if (risk == null) return List.of();

        var faultRuns = attempts.stream().filter(a -> a.kind().equals(AttemptResult.FAULT)).toList();
        var baselines = attempts.stream().filter(a -> a.kind().equals(AttemptResult.BASELINE)).toList();
        int needed = plan.confirm().minFailing();
        Label label;
        String reason;
        List<String> evidence = List.of();

        if (plan.fault() == null) {
            label = Label.NOT_TESTED;
            reason = "The plan has no fault, so nothing broke the app at this code.";
        } else {
            int times = plan.fault().count();
            var fired = faultRuns.stream().filter(a -> a.faultsFired() != null && a.faultsFired() >= times).toList();
            var failing = fired.stream().filter(AttemptResult::dataFailed).toList();
            var failedBaselines = baselines.stream().filter(AttemptResult::dataFailed).toList();
            if (fired.isEmpty()) {
                label = Label.NOT_TESTED;
                reason = "The fault never fired all %d times in any of the %d runs, so the runs cannot say either way.".formatted(times, faultRuns.size());
            } else if (failing.size() >= needed && failedBaselines.isEmpty()) {
                label = Label.CONFIRMED;
                reason = "%s failed in %d of %d runs with the fault%s.".formatted(failedChecks(failing.getFirst()), failing.size(), faultRuns.size(),
                    baselines.isEmpty() ? "" : ", and passed in the %d baseline run%s without it".formatted(baselines.size(), baselines.size() == 1 ? "" : "s"));
                evidence = failing.getFirst().checks().stream().filter(c -> !c.passed() && Plan.DATA_CHECKS.contains(c.name()))
                    .flatMap(c -> c.offenders().stream()).limit(MAX_EVIDENCE).toList();
            } else if (failing.size() >= needed) {
                label = Label.NOT_TESTED;
                reason = "The checks failed with the fault, but also in %d of %d baseline runs without it, so the fault is not what breaks them."
                    .formatted(failedBaselines.size(), baselines.size());
            } else if (!failing.isEmpty()) {
                label = Label.SUSPECTED;
                reason = "%s failed in %d of %d runs with the fault; confirming it takes %d.".formatted(failedChecks(failing.getFirst()),
                    failing.size(), faultRuns.size(), needed);
            } else {
                label = Label.SUSPECTED;
                reason = "The fault fired in %d of %d runs and every check passed: this code is suspect, but the runs could not break it."
                    .formatted(fired.size(), faultRuns.size());
            }
        }
        return List.of(new Finding(label, reason, risk.id(), risk.pattern(), risk.title(), risk.location(), risk.file(), risk.line(),
            plan.journey(), risk.confidence(), risk.why(), evidence));
    }

    private static String failedChecks(AttemptResult attempt) {
        return attempt.checks().stream().filter(c -> !c.passed() && Plan.DATA_CHECKS.contains(c.name()))
            .map(CheckResult::name).collect(Collectors.joining(", "));
    }
}
