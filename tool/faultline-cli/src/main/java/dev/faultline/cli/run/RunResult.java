package dev.faultline.cli.run;

import java.util.List;

import dev.faultline.cli.report.Finding;

/**
 * result.json: every run of the plan (with the fault, then baselines without it), the labelled findings, and the
 * LLM-drafted rules that did not count, with why.
 */
public record RunResult(String verdict, List<AttemptResult> attempts, List<Finding> findings, String reproduce, List<DroppedRule> droppedRules) {

    public RunResult(String verdict, List<AttemptResult> attempts, List<Finding> findings, String reproduce) {
        this(verdict, attempts, findings, reproduce, List.of());
    }

    /** A drafted rule left out of every verdict. */
    public record DroppedRule(String id, String reason) {}
}
