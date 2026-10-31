package dev.faultline.cli.check;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.INFO;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;

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
        var sent = byId(history);
        return List.of(
            noLost(sent, applied, destroyed),
            noDuplicates(sent, applied),
            noUnexpected(sent, applied));
    }

    public static List<CheckResult> check(List<HistoryEntry> history, Map<String, Integer> applied) {
        return check(history, applied, Set.of());
    }

    /** Every request the app accepted was applied. info requests are allowed to be missing. */
    static CheckResult noLost(Map<String, HistoryEntry.Status> sent, Map<String, Integer> applied, Set<String> destroyed) {
        var lost = sent.entrySet().stream()
            .filter(e -> e.getValue() == OK && times(applied, e.getKey()) == 0 && !destroyed.contains(e.getKey()))
            .map(Map.Entry::getKey)
            .toList();
        return CheckResult.of("no-lost", lost);
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

    /** An app rule: every row its SQL returned is an offender. */
    public static CheckResult rule(String id, List<String> rows) {
        return CheckResult.of(id, rows);
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
