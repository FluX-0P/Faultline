package dev.faultline.cli.check;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.INFO;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.faultline.cli.history.HistoryEntry;

/**
 * Compares what was sent (the history) with what actually happened (the rows in the plan's effects table).
 * ok requests must have exactly one row, fail requests none, and info requests at most one. A request the client sent
 * twice counts once, as ok if either copy was accepted. Requests the fault itself destroyed (a corrupted message) are
 * excused from no-lost.
 */
public final class Checker {
    // pay-1, pay-2 ... pay-10, rather than pay-1, pay-10, pay-100, pay-11
    private static final Comparator<String> NATURAL = Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private Checker() {}

    /**
     * @param history every request sent, sorted by index
     * @param applied how many rows each request ID has
     * @param destroyed IDs whose message a fault corrupted on purpose
     */
    public static List<CheckResult> check(List<HistoryEntry> history, Map<String, Integer> applied, Set<String> destroyed) {
        return check(history, applied, new Destroyed(destroyed, 0));
    }

    public static List<CheckResult> check(List<HistoryEntry> history, Map<String, Integer> applied, Destroyed destroyed) {
        var sent = byId(history);
        return List.of(
            noLost(sent, applied, destroyed),
            noDuplicates(sent, applied),
            noUnexpected(sent, applied));
    }

    public static List<CheckResult> check(List<HistoryEntry> history, Map<String, Integer> applied) {
        return check(history, applied, Destroyed.NONE);
    }

    /**
     * Every request the app accepted was applied. info requests are allowed to be missing, and so are the requests a
     * fault destroyed: by ID, and as many as it destroyed without a readable ID.
     */
    static CheckResult noLost(Map<String, HistoryEntry.Status> sent, Map<String, Integer> applied, Destroyed destroyed) {
        var lost = sent.entrySet().stream()
            .filter(e -> e.getValue() == OK && times(applied, e.getKey()) == 0 && !destroyed.ids().contains(e.getKey()))
            .map(Map.Entry::getKey)
            .toList();
        if (destroyed.unreadable() == 0 || lost.isEmpty()) return CheckResult.of("no-lost", lost);
        // which request an unreadable message was cannot be told, only how many there were
        if (lost.size() <= destroyed.unreadable()) {
            return CheckResult.of("no-lost", List.of(), "%d request(s) not applied, as many as the messages the fault corrupted that name no request Faultline can read"
                .formatted(lost.size()));
        }
        return CheckResult.of("no-lost", lost, "up to %d of these are the messages the fault corrupted that name no request Faultline can read"
            .formatted(destroyed.unreadable()));
    }

    /** No request was applied more than once, whatever its status. */
    static CheckResult noDuplicates(Map<String, HistoryEntry.Status> sent, Map<String, Integer> applied) {
        var duplicated = new ArrayList<String>();
        for (var id : sent.keySet()) {
            int n = times(applied, id);
            if (n > 1) duplicated.add(id + " (" + n + "x)");
        }
        for (var id : unsent(sent, applied)) {
            int n = applied.get(id);
            if (n > 1) duplicated.add(id + " (" + n + "x)");
        }
        return CheckResult.of("no-duplicates", duplicated);
    }

    /** Nothing was applied that was never sent, or that definitely failed. */
    static CheckResult noUnexpected(Map<String, HistoryEntry.Status> sent, Map<String, Integer> applied) {
        var unexpected = new ArrayList<String>();
        sent.forEach((id, status) -> {
            if (status == FAIL && times(applied, id) > 0) unexpected.add(id + " (sent as fail)");
        });
        for (var id : unsent(sent, applied)) {
            unexpected.add(id + " (never sent)");
        }
        return CheckResult.of("no-unexpected", unexpected);
    }

    /**
     * Requests with the same key were applied in the order they happened. A happened before B when the app had accepted
     * A before the client sent B; only those pairs count, so two requests racing each other can go either way.
     *
     * @param keyOf the key of each request ID, such as its account
     * @param appliedOrder request IDs in the order their rows were added
     */
    public static CheckResult perKeyOrder(List<HistoryEntry> history, Map<String, String> keyOf, List<String> appliedOrder) {
        var position = new HashMap<String, Integer>();
        for (int i = 0; i < appliedOrder.size(); i++) position.putIfAbsent(appliedOrder.get(i), i);
        var sentAt = new HashMap<String, Instant>();
        var acceptedAt = new HashMap<String, Instant>();
        for (var e : history) {
            sentAt.merge(e.id(), e.sentAt(), (a, b) -> a.isBefore(b) ? a : b);
            if (e.status() == OK) acceptedAt.merge(e.id(), e.doneAt(), (a, b) -> a.isBefore(b) ? a : b);
        }

        var byKey = new TreeMap<String, List<String>>(NATURAL);
        for (var id : position.keySet()) {
            String key = keyOf.get(id);
            if (key != null && acceptedAt.containsKey(id)) byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
        }
        var offenders = new ArrayList<String>();
        byKey.forEach((key, ids) -> {
            ids.sort(Comparator.comparing(position::get));
            for (int i = 0; i < ids.size(); i++) {
                for (int j = i + 1; j < ids.size(); j++) {
                    String earlier = ids.get(i);
                    String later = ids.get(j);
                    if (acceptedAt.get(later).isBefore(sentAt.get(earlier))) {
                        offenders.add("%s: %s was applied before %s, which happened first".formatted(key, earlier, later));
                    }
                }
            }
        });
        return CheckResult.of("per-key-order", offenders);
    }

    /** Every request the app applied also reached the downstream topic, such as a "payment completed" event. */
    public static CheckResult noLostDownstream(Map<String, Integer> applied, Set<String> published, String topic) {
        var lost = applied.keySet().stream().filter(id -> !published.contains(id)).sorted(NATURAL)
            .map(id -> id + " (applied, but no event on " + topic + ")").toList();
        return CheckResult.of("no-lost-downstream", lost);
    }

    /**
     * After the fault ended, the app caught up within {@code budget}: every request it had accepted before the end was
     * applied no later than {@code budget} after it.
     *
     * @param faultEnded when the last planned fault ended, null if none did
     * @param firstApplied when each request ID's effect first appeared
     */
    public static CheckResult recoveryWithin(List<HistoryEntry> history, Instant faultEnded, Duration budget, Map<String, Instant> firstApplied) {
        if (faultEnded == null) return CheckResult.of("recovery-within", List.of("the fault never ended, so there was nothing to recover from"));
        var deadline = faultEnded.plus(budget);
        var sentBefore = new LinkedHashMap<String, Instant>();
        for (var e : history) {
            if (e.status() == OK && e.sentAt().isBefore(faultEnded)) sentBefore.merge(e.id(), e.sentAt(), (a, b) -> a.isBefore(b) ? a : b);
        }
        var late = new ArrayList<String>();
        Duration caughtUp = Duration.ZERO;
        for (var id : sentBefore.keySet().stream().sorted(NATURAL).toList()) {
            Instant at = firstApplied.get(id);
            if (at == null) {
                late.add(id + " (never applied)");
            } else if (at.isAfter(deadline)) {
                late.add("%s (applied %.1fs after the fault ended)".formatted(id, Duration.between(faultEnded, at).toMillis() / 1000.0));
            } else if (at.isAfter(faultEnded.plus(caughtUp))) {
                caughtUp = Duration.between(faultEnded, at);
            }
        }
        String note = late.isEmpty()
            ? "caught up %.1fs after the fault ended (allowed %ds)".formatted(caughtUp.toMillis() / 1000.0, budget.toSeconds())
            : "%d requests sent before the fault ended were not applied within %ds of its end".formatted(late.size(), budget.toSeconds());
        return CheckResult.of("recovery-within", late, note);
    }

    /** An app rule: every row its SQL returned is an offender. */
    public static CheckResult rule(String id, List<String> rows) {
        return CheckResult.of(id, rows);
    }

    public static final String SQL_FAILED = "its SQL does not run: ";

    /** An app rule whose SQL the database refused: it fails, and says why. */
    public static CheckResult brokenRule(String id, String error) {
        String first = error == null ? "" : error.lines().findFirst().orElse("");
        return CheckResult.of(id, List.of(first), SQL_FAILED + first);
    }

    /** Every planned fault really fired. Without this, a PASS could just mean the fault never happened. */
    public static CheckResult faultsFired(int planned, int fired) {
        return CheckResult.of("faults-fired", fired == planned ? List.of() : List.of("fired " + fired + " of " + planned));
    }

    /** Each request ID once: ok if any copy was accepted, fail if every copy failed, info otherwise. */
    static Map<String, HistoryEntry.Status> byId(List<HistoryEntry> history) {
        var status = new LinkedHashMap<String, HistoryEntry.Status>();
        for (var e : history) {
            status.merge(e.id(), e.status(), (a, b) -> a == OK || b == OK ? OK : a == FAIL && b == FAIL ? FAIL : INFO);
        }
        return status;
    }

    private static int times(Map<String, Integer> applied, String id) {
        return applied.getOrDefault(id, 0);
    }

    /** IDs in the database that the generator never sent, in natural order. */
    private static List<String> unsent(Map<String, HistoryEntry.Status> sent, Map<String, Integer> applied) {
        return applied.keySet().stream().filter(id -> !sent.containsKey(id)).sorted(NATURAL).toList();
    }
}
