package dev.faultline.cli.plan;

import java.util.List;

/** The LLM's part of a plan: the journey, the request template, how a request is matched to its row, and the reset. */
public record PlanDraft(String journey, Request request, String correlateBy, Effect effect, List<String> reset, String why) {

    public record Request(String method, String path, String body) {}

    public record Effect(String table, String column) {}
}
