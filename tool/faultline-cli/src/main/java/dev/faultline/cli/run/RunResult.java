package dev.faultline.cli.run;

import java.util.List;

import dev.faultline.cli.report.Finding;

/** result.json: every run of the plan (with the fault, then baselines without it), and the labelled findings. */
public record RunResult(String verdict, List<AttemptResult> attempts, List<Finding> findings, String reproduce) {}
