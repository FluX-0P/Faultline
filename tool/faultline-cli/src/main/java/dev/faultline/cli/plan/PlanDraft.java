package dev.faultline.cli.plan;

import java.util.List;

/**
 * The LLM's part of a plan: the journey, the request template, how a request is matched to its row, the reset, and the
 * rules the data must keep. {@code responseField} is only asked for in a repo of several services: the field of the
 * app's JSON answer that carries the ID the app made for the request, empty (or null) when the effect holds a request
 * field.
 */
public record PlanDraft(String journey, Request request, String correlateBy, String responseField, Effect effect, List<String> reset,
                        List<Rule> rules, String why) {

    public PlanDraft(String journey, Request request, String correlateBy, Effect effect, List<String> reset, List<Rule> rules, String why) {
        this(journey, request, correlateBy, null, effect, reset, rules, why);
    }

    /** The response field, or null when the draft names none. */
    public String answerField() {
        return responseField == null || responseField.isBlank() ? null : responseField.strip();
    }

    /** {@code path} without the slips an LLM makes around a bare /: a trailing comma, full stop or ?, or nothing at all. */
    public record Request(String method, String path, String body) {
        public Request {
            if (path != null) {
                path = path.strip().replaceAll("[,;.?]+$", "").strip();
                if (path.isEmpty()) path = "/";
            }
        }
    }

    public record Effect(String table, String column) {}

    /** {@code why} comes first: what must hold, then the SQL that finds where it does not. */
    public record Rule(String id, String why, String sql) {}
}
