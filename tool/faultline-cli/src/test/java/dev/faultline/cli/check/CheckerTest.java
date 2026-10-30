package dev.faultline.cli.check;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.INFO;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.history.HistoryEntry;
import org.junit.jupiter.api.Test;

class CheckerTest {
    private static final long START = 1_000_000;

    @Test
    void everyPaymentAppliedOncePasses() {
        var history = List.of(entry(0, "acct-1", 100, OK), entry(1, "acct-2", 200, OK));
        var results = check(history, Map.of("p0", 1, "p1", 1), Map.of("acct-1", START - 100, "acct-2", START - 200));
        assertThat(results).allMatch(CheckResult::passed);
    }

    @Test
    void missingOkPaymentIsLost() {
        var history = List.of(entry(0, "acct-1", 100, OK), entry(1, "acct-1", 200, OK));
        var results = check(history, Map.of("p0", 1), Map.of("acct-1", START - 100));
        assertThat(result(results, "no-lost").offenders()).containsExactly("p1");
        assertThat(result(results, "balances").passed()).isTrue();
    }

    @Test
    void missingInfoPaymentIsNotLost() {
        var history = List.of(entry(0, "acct-1", 100, INFO));
        var results = check(history, Map.of(), Map.of("acct-1", START));
        assertThat(results).allMatch(CheckResult::passed);
    }

    @Test
    void appliedInfoPaymentRaisesNoFalseAlarm() {
        // timed out at the client but went through: allowed once, and the balance must count it
        var history = List.of(entry(0, "acct-1", 100, INFO));
        var results = check(history, Map.of("p0", 1), Map.of("acct-1", START - 100));
        assertThat(results).allMatch(CheckResult::passed);
    }

    @Test
    void doubleAppliedPaymentFailsDuplicatesAndBalances() {
        // bug B1: committed, crashed before acknowledge, redelivered, applied again
        var history = List.of(entry(0, "acct-1", 100, OK));
        var results = check(history, Map.of("p0", 2), Map.of("acct-1", START - 200));
        assertThat(result(results, "no-duplicates").offenders()).containsExactly("p0 (2x)");
        assertThat(result(results, "balances").offenders()).containsExactly("acct-1 (expected 999900, actual 999800)");
        assertThat(result(results, "no-lost").passed()).isTrue();
    }

    @Test
    void duplicatedInfoPaymentIsStillADuplicate() {
        var history = List.of(entry(0, "acct-1", 100, INFO));
        var results = check(history, Map.of("p0", 2), Map.of("acct-1", START - 200));
        assertThat(result(results, "no-duplicates").offenders()).containsExactly("p0 (2x)");
    }

    @Test
    void appliedFailPaymentIsUnexpected() {
        var history = List.of(entry(0, "acct-1", 100, FAIL));
        var results = check(history, Map.of("p0", 1), Map.of("acct-1", START - 100));
        assertThat(result(results, "no-unexpected").offenders()).containsExactly("p0 (sent as fail)");
    }

    @Test
    void paymentThatWasNeverSentIsUnexpected() {
        var results = check(List.of(), Map.of("pay-old-3", 1), Map.of("acct-1", START));
        assertThat(result(results, "no-unexpected").offenders()).containsExactly("pay-old-3 (never sent)");
    }

    @Test
    void debitWithoutPaymentRowFailsBalances() {
        var history = List.of(entry(0, "acct-1", 100, OK));
        var results = check(history, Map.of("p0", 1), Map.of("acct-1", START - 300));
        assertThat(result(results, "balances").offenders()).containsExactly("acct-1 (expected 999900, actual 999700)");
    }

    @Test
    void offendersAreInNaturalOrder() {
        var balances = new HashMap<String, Long>();
        for (var account : List.of("acct-10", "acct-2", "acct-100", "acct-1")) balances.put(account, 0L);
        var results = check(List.of(), Map.of(), balances);
        assertThat(result(results, "balances").offenders())
            .extracting(s -> s.substring(0, s.indexOf(' ')))
            .containsExactly("acct-1", "acct-2", "acct-10", "acct-100");
    }

    @Test
    void faultsFiredPassesOnlyWhenEveryPlannedFaultFired() {
        assertThat(Checker.faultsFired(3, 3).passed()).isTrue();
        assertThat(Checker.faultsFired(3, 2).offenders()).containsExactly("fired 2 of 3");
        assertThat(Checker.faultsFired(3, 4).offenders()).containsExactly("fired 4 of 3");
    }

    private static List<CheckResult> check(List<HistoryEntry> history, Map<String, Integer> applied, Map<String, Long> balances) {
        return Checker.check(history, applied, balances, START);
    }

    private static CheckResult result(List<CheckResult> results, String name) {
        return results.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static HistoryEntry entry(int index, String account, long amount, HistoryEntry.Status status) {
        var now = Instant.now();
        return new HistoryEntry(index, "p" + index, account, amount, status, status == OK ? 202 : null, null, now, now);
    }
}
