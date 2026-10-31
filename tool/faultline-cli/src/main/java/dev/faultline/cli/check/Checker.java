package dev.faultline.cli.check;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.faultline.cli.history.HistoryEntry;

/**
 * Compares what was sent (the history) with what actually happened (the rows in the plan's effects table).
 * ok requests must have exactly one row, fail requests none, and info requests at most one.
 */
public final class Checker {
    // pay-1, pay-2 ... pay-10, rather than pay-1, pay-10, pay-100, pay-11
    private static final Comparator<String> NATURAL = Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private Checker() {}

    /**
     * @param history every request sent, sorted by index
     * @param applied how many rows each request ID has
     */
    public static List<CheckResult> check(List<HistoryEntry> history, Map<String, Integer> applied) {
        return List.of(
            noLost(history, applied),
            noDuplicates(history, applied),
            noUnexpected(history, applied));
    }

    /** Every request the app accepted was applied. info requests are allowed to be missing. */
    static CheckResult noLost(List<HistoryEntry> history, Map<String, Integer> applied) {
        var lost = history.stream()
            .filter(e -> e.status() == OK && times(applied, e.id()) == 0)
            .map(HistoryEntry::id)
            .toList();
        return CheckResult.of("no-lost", lost);
    }

    /** No request was applied more than once, whatever its status. */
    static CheckResult noDuplicates(List<HistoryEntry> history, Map<String, Integer> applied) {
        var duplicated = new ArrayList<String>();
        for (var e : history) {
            int n = times(applied, e.id());
            if (n > 1) duplicated.add(e.id() + " (" + n + "x)");
        }
        for (var id : unsent(history, applied)) {
            int n = applied.get(id);
            if (n > 1) duplicated.add(id + " (" + n + "x)");
        }
        return CheckResult.of("no-duplicates", duplicated);
    }

    /** Nothing was applied that was never sent, or that definitely failed. */
    static CheckResult noUnexpected(List<HistoryEntry> history, Map<String, Integer> applied) {
        var unexpected = new ArrayList<String>();
        for (var e : history) {
            if (e.status() == FAIL && times(applied, e.id()) > 0) unexpected.add(e.id() + " (sent as fail)");
        }
        for (var id : unsent(history, applied)) {
            unexpected.add(id + " (never sent)");
        }
        return CheckResult.of("no-unexpected", unexpected);
    }

    /** Every planned fault really fired. Without this, a PASS could just mean the fault never happened. */
    public static CheckResult faultsFired(int planned, int fired) {
        return CheckResult.of("faults-fired", fired == planned ? List.of() : List.of("fired " + fired + " of " + planned));
    }

    private static int times(Map<String, Integer> applied, String id) {
        return applied.getOrDefault(id, 0);
    }

    /** IDs in the database that the generator never sent, in natural order. */
    private static List<String> unsent(List<HistoryEntry> history, Map<String, Integer> applied) {
        Set<String> sent = new HashSet<>();
        for (var e : history) sent.add(e.id());
        return applied.keySet().stream().filter(id -> !sent.contains(id)).sorted(NATURAL).toList();
    }
}
