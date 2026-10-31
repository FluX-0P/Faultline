package dev.faultline.cli.report;

import java.util.List;
import java.util.stream.Collectors;

import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.report.Finding.Label;

/**
 * Labels the plan's risk from a run's checks. Confirmed needs both halves: the fault fired as planned, and a data
 * check (no-lost, no-duplicates, no-unexpected) failed. A flagged risk the run could not break stays suspected.
 */
public final class Findings {
    static final int MAX_EVIDENCE = 50;

    private Findings() {}

    /** @param fired how many faults the agent fired, null when the plan has none */
    public static List<Finding> of(Plan plan, List<CheckResult> checks, Integer fired) {
        var risk = plan.risk();
        if (risk == null) return List.of();

        var failed = checks.stream().filter(c -> !c.passed() && Plan.DATA_CHECKS.contains(c.name())).toList();
        Label label;
        String reason;
        List<String> evidence = List.of();
        if (plan.fault() == null) {
            label = Label.NOT_TESTED;
            reason = "The plan has no fault, so nothing broke the app at this code.";
        } else if (fired == null || fired < plan.fault().times()) {
            label = Label.NOT_TESTED;
            reason = "The fault fired %d of %d times, so this run cannot say either way.".formatted(fired == null ? 0 : fired, plan.fault().times());
        } else if (!failed.isEmpty()) {
            label = Label.CONFIRMED;
            reason = "%s failed after %d planned crashes at %s.".formatted(
                failed.stream().map(c -> c.name() + " (" + c.offenders().size() + ")").collect(Collectors.joining(", ")),
                fired, plan.fault().point());
            evidence = failed.stream().flatMap(c -> c.offenders().stream()).limit(MAX_EVIDENCE).toList();
        } else {
            label = Label.SUSPECTED;
            reason = "The fault fired all %d times and every check passed: the scan flagged this code, but the run could not break it."
                .formatted(fired);
        }
        return List.of(new Finding(label, reason, risk.id(), risk.pattern(), risk.title(), risk.location(), risk.file(), risk.line(),
            plan.journey(), risk.confidence(), risk.why(), evidence));
    }
}
