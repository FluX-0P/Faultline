package dev.faultline.cli.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.check.Checker;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.report.Finding.Label;
import dev.faultline.cli.run.AttemptResult;
import dev.faultline.cli.run.RunResult;

/**
 * Labels the plan's risk from all its runs. Confirmed needs three things: the fault fired as planned, a data check
 * (every check but faults-fired) failed in at least confirm.min_failing of those runs, and every baseline run
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
        Set<String> ignored = ignored(plan, attempts);
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
            var failing = fired.stream().filter(a -> a.dataFailed(ignored)).toList();
            var failedBaselines = baselines.stream().filter(a -> a.dataFailed(ignored)).toList();
            if (fired.isEmpty()) {
                label = Label.NOT_TESTED;
                reason = "The fault never fired all %d times in any of the %d runs, so the runs cannot say either way.".formatted(times, faultRuns.size());
            } else if (failing.size() >= needed && failedBaselines.isEmpty()) {
                label = Label.CONFIRMED;
                reason = "%s failed in %d of %d runs with the fault%s.".formatted(failedChecks(failing.getFirst(), ignored), failing.size(), faultRuns.size(),
                    baselines.isEmpty() ? "" : ", and passed in the %d baseline run%s without it".formatted(baselines.size(), baselines.size() == 1 ? "" : "s"));
                evidence = failing.getFirst().checks().stream().filter(c -> AttemptResult.dataCheckFailed(c) && !ignored.contains(c.name()))
                    .flatMap(c -> c.offenders().stream()).limit(MAX_EVIDENCE).toList();
            } else if (failing.size() >= needed) {
                label = Label.NOT_TESTED;
                reason = "The checks failed with the fault, but also in %d of %d baseline runs without it, so the fault is not what breaks them."
                    .formatted(failedBaselines.size(), baselines.size());
            } else if (!failing.isEmpty()) {
                label = Label.SUSPECTED;
                reason = "%s failed in %d of %d runs with the fault; confirming it takes %d.".formatted(failedChecks(failing.getFirst(), ignored),
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

    /**
     * The LLM's rules that do not count: one that failed in a baseline run without the fault is wrong, and one that no
     * baseline ran is unchecked. Rules a person wrote, or vouched for by taking out {@code drafted}, always count.
     */
    public static List<RunResult.DroppedRule> droppedRules(Plan plan, List<AttemptResult> attempts) {
        var baselines = attempts.stream().filter(a -> a.kind().equals(AttemptResult.BASELINE)).toList();
        var dropped = new ArrayList<RunResult.DroppedRule>();
        for (var rule : plan.rules() == null ? List.<Plan.Rule>of() : plan.rules()) {
            if (!rule.byLlm()) continue;
            var failedIn = baselines.stream()
                .filter(b -> b.checks().stream().anyMatch(c -> c.name().equals(rule.id()) && !c.passed())).map(AttemptResult::name).toList();
            var broken = attempts.stream().flatMap(a -> a.checks().stream())
                .filter(c -> c.name().equals(rule.id()) && c.note() != null && c.note().startsWith(Checker.SQL_FAILED)).findFirst();
            if (broken.isPresent()) {
                dropped.add(new RunResult.DroppedRule(rule.id(), "drafted by the LLM, and " + broken.get().note()));
            } else if (baselines.isEmpty()) {
                dropped.add(new RunResult.DroppedRule(rule.id(), "drafted by the LLM, and no baseline run without the fault checked it"));
            } else if (!failedIn.isEmpty()) {
                dropped.add(new RunResult.DroppedRule(rule.id(),
                    "drafted by the LLM, and failed in " + String.join(", ", failedIn) + " without the fault: the rule is wrong, not the app"));
            }
        }
        return dropped;
    }

    /** The checks no verdict counts: the dropped rules. */
    public static Set<String> ignored(Plan plan, List<AttemptResult> attempts) {
        return droppedRules(plan, attempts).stream().map(RunResult.DroppedRule::id).collect(Collectors.toSet());
    }

    private static String failedChecks(AttemptResult attempt, Set<String> ignored) {
        return attempt.checks().stream().filter(c -> AttemptResult.dataCheckFailed(c) && !ignored.contains(c.name()))
            .map(CheckResult::name).collect(Collectors.joining(", "));
    }
}
