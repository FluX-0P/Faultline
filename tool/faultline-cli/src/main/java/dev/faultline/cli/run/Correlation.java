package dev.faultline.cli.run;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.plan.Plan;

/**
 * Which request each ID in the effects table belongs to. Usually the table holds the request's own ID. With
 * correlate_by.response_field the app makes the ID and answers the request with it, and the history ties each one to its
 * request. A request that got no answer has no ID to match its effect by: a row of an unknown ID is taken for one of
 * those, as many as there are, and only the rest count as never sent.
 */
final class Correlation {
    private static final Correlation SAME = new Correlation(null, 0);

    /** Request ID by effect ID; null when they are the same. */
    private final Map<String, String> requestOf;
    private final int unanswered;
    private int unmatched;

    private Correlation(Map<String, String> requestOf, int unanswered) {
        this.requestOf = requestOf;
        this.unanswered = unanswered;
    }

    static Correlation of(Plan.CorrelateBy by, List<HistoryEntry> history) {
        if (by.responseField() == null) return SAME;
        var requestOf = new HashMap<String, String>();
        int unanswered = 0;
        for (var e : history) {
            if (e.effect() != null) requestOf.putIfAbsent(e.effect(), e.id());
            else if (e.status() != HistoryEntry.Status.FAIL) unanswered++;
        }
        return new Correlation(requestOf, unanswered);
    }

    /** Rows per request, from rows per effect ID. */
    Map<String, Integer> applied(Map<String, Integer> byEffect) {
        if (requestOf == null) return byEffect;
        var applied = new HashMap<String, Integer>();
        int left = unanswered;
        for (var e : new TreeMap<>(byEffect).entrySet()) {
            String request = requestOf.get(e.getKey());
            if (request != null) {
                applied.merge(request, e.getValue(), Integer::sum);
            } else if (left > 0) {
                left--;
                unmatched++;
            } else {
                applied.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return applied;
    }

    /** How many effect IDs {@link #applied} took for requests that got no answer. */
    int unmatched() {
        return unmatched;
    }

    Set<String> requests(Set<String> effectIds) {
        return requestOf == null ? effectIds : effectIds.stream().map(this::request).collect(Collectors.toSet());
    }

    List<String> order(List<String> effectIds) {
        return requestOf == null ? effectIds : effectIds.stream().map(this::request).toList();
    }

    Map<String, Instant> firstApplied(Map<String, Instant> byEffect) {
        if (requestOf == null) return byEffect;
        var first = new HashMap<String, Instant>();
        byEffect.forEach((id, at) -> first.merge(request(id), at, (a, b) -> a.isBefore(b) ? a : b));
        return first;
    }

    private String request(String effectId) {
        return requestOf.getOrDefault(effectId, effectId);
    }
}
