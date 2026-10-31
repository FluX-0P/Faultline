package dev.faultline.cli.plan;

import java.util.List;

/**
 * The LLM's part of a plan: the journey, the request template, how a request is matched to its row, the reset, and the
 * rules the data must keep.
 */
public record PlanDraft(String journey, Request request, String correlateBy, Effect effect, List<String> reset, List<Rule> rules, String why) {

    public record Request(String method, String path, String body) {}

    public record Effect(String table, String column) {}

    /** {@code why} comes first: what must hold, then the SQL that finds where it does not. */
    public record Rule(String id, String why, String sql) {}
}
