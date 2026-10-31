package dev.faultline.cli.run;

import java.util.List;

import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.report.Finding;

/** result.json: what a run sent, how the drain went, every check's verdict, and the labelled findings. */
public record RunResult(String verdict, Sent sent, Drain drain, Integer restarts, Integer faultsFired,
                        List<CheckResult> checks, List<Finding> findings, String reproduce) {

    public record Sent(long ok, long fail, long info, long millis) {}

    public record Drain(long rows, long backlog, boolean settled, long waitedMillis) {}
}
