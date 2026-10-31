package dev.faultline.cli.run;

import java.time.Instant;

/** run.json: how a run was started. The run folder's plan.yaml has everything else. */
public record RunInfo(String plan, long seed, int count, int rate, int runs, int baselines, Instant startedAt) {}
