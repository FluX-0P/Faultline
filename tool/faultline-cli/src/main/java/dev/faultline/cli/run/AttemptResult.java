package dev.faultline.cli.run;

import java.util.List;

import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.plan.Plan;

/**
 * One run of a plan, in its own folder: what it sent, how the drain went, and every check's verdict.
 * {@code kind} is fault (the plan as written) or baseline (the same plan without its fault).
 */
public record AttemptResult(String name, String kind, Sent sent, Drain drain, Integer restarts, Integer faultsFired,
                            List<CheckResult> checks) {
    public static final String FAULT = "fault";
    public static final String BASELINE = "baseline";

    public record Sent(long ok, long fail, long info, long millis) {}

    public record Drain(long rows, long backlog, boolean settled, long waitedMillis) {}

    public boolean allPassed() {
        return checks.stream().allMatch(CheckResult::passed);
    }

    /** Whether a data check failed: every check but faults-fired, which is about the fault, not the data. */
    public boolean dataFailed() {
        return checks.stream().anyMatch(AttemptResult::dataCheckFailed);
    }

    public static boolean dataCheckFailed(CheckResult c) {
        return !c.passed() && !c.name().equals(Plan.FAULTS_FIRED);
    }
}
