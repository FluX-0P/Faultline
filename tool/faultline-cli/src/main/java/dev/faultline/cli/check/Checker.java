package dev.faultline.cli.check;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.faultline.cli.history.HistoryEntry;

/**
 * Compares what was sent (the history) with what actually happened (the database).
 * ok payments must appear exactly once, fail payments never, and info payments at most once.
 */
public final class Checker {
    // acct-1, acct-2 ... acct-10, rather than acct-1, acct-10, acct-100, acct-11
    private static final Comparator<String> NATURAL = Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private Checker() {}

    /**
     * @param history  every payment sent, sorted by index
     * @param applied  how many times each payment ID appears in the database
     * @param balances every account's current balance
     */
    public static List<CheckResult> check(List<HistoryEntry> history, Map<String, Integer> applied,
                                          Map<String, Long> balances, long initialBalance) {
        return List.of(
            noLost(history, applied),
            noDuplicates(history, applied),
            noUnexpected(history, applied),
            balances(history, applied, balances, initialBalance));
    }

    /** Every payment the app accepted was applied. info payments are allowed to be missing. */
    static CheckResult noLost(List<HistoryEntry> history, Map<String, Integer> applied) {
        var lost = history.stream()
            .filter(e -> e.status() == OK && times(applied, e.paymentId()) == 0)
            .map(HistoryEntry::paymentId)
            .toList();
        return CheckResult.of("no-lost", lost);
    }

    /** No payment was applied more than once, whatever its status. */
    static CheckResult noDuplicates(List<HistoryEntry> history, Map<String, Integer> applied) {
        var duplicated = new ArrayList<String>();
        for (var e : history) {
            int n = times(applied, e.paymentId());
            if (n > 1) duplicated.add(e.paymentId() + " (" + n + "x)");
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
            if (e.status() == FAIL && times(applied, e.paymentId()) > 0) unexpected.add(e.paymentId() + " (sent as fail)");
        }
        for (var id : unsent(history, applied)) {
            unexpected.add(id + " (never sent)");
        }
        return CheckResult.of("no-unexpected", unexpected);
    }

    /**
     * Each account's balance equals the starting balance minus every applied payment, counted once.
     * A double debit therefore fails here as well as in no-duplicates, while an info payment that did go
     * through raises no false alarm.
     */
    static CheckResult balances(List<HistoryEntry> history, Map<String, Integer> applied,
                                Map<String, Long> balances, long initialBalance) {
        var debited = new HashMap<String, Long>();
        for (var e : history) {
            if (times(applied, e.paymentId()) > 0) debited.merge(e.accountId(), e.amountMinor(), Long::sum);
        }
        var wrong = new ArrayList<String>();
        for (var account : balances.keySet().stream().sorted(NATURAL).toList()) {
            long expected = initialBalance - debited.getOrDefault(account, 0L);
            long actual = balances.get(account);
            if (actual != expected) wrong.add(account + " (expected " + expected + ", actual " + actual + ")");
        }
        return CheckResult.of("balances", wrong);
    }

    private static int times(Map<String, Integer> applied, String paymentId) {
        return applied.getOrDefault(paymentId, 0);
    }

    /** Payment IDs in the database that the generator never sent, in natural order. */
    private static List<String> unsent(List<HistoryEntry> history, Map<String, Integer> applied) {
        Set<String> sent = new HashSet<>();
        for (var e : history) sent.add(e.paymentId());
        return applied.keySet().stream().filter(id -> !sent.contains(id)).sorted(NATURAL).toList();
    }
}
