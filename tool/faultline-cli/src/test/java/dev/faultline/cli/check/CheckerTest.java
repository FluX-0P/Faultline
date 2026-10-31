package dev.faultline.cli.check;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.INFO;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.faultline.cli.history.HistoryEntry;
import org.junit.jupiter.api.Test;

class CheckerTest {

    @Test
    void everyRequestAppliedOncePasses() {
        var results = Checker.check(List.of(entry(0, OK), entry(1, OK)), Map.of("p0", 1, "p1", 1));
        assertThat(results).extracting(CheckResult::name).containsExactly("no-lost", "no-duplicates", "no-unexpected");
        assertThat(results).allMatch(CheckResult::passed);
    }

    @Test
    void missingOkRequestIsLost() {
        var results = Checker.check(List.of(entry(0, OK), entry(1, OK)), Map.of("p0", 1));
        assertThat(result(results, "no-lost").offenders()).containsExactly("p1");
    }

    @Test
    void missingInfoRequestIsNotLost() {
        assertThat(Checker.check(List.of(entry(0, INFO)), Map.of())).allMatch(CheckResult::passed);
    }

    @Test
    void appliedInfoRequestRaisesNoFalseAlarm() {
        // timed out at the client but went through: allowed once
        assertThat(Checker.check(List.of(entry(0, INFO)), Map.of("p0", 1))).allMatch(CheckResult::passed);
    }

    @Test
    void doubleAppliedRequestIsADuplicate() {
        // bug B1: committed, crashed before acknowledge, redelivered, applied again
        var results = Checker.check(List.of(entry(0, OK)), Map.of("p0", 2));
        assertThat(result(results, "no-duplicates").offenders()).containsExactly("p0 (2x)");
        assertThat(result(results, "no-lost").passed()).isTrue();
    }

    @Test
    void duplicatedInfoRequestIsStillADuplicate() {
        var results = Checker.check(List.of(entry(0, INFO)), Map.of("p0", 2));
        assertThat(result(results, "no-duplicates").offenders()).containsExactly("p0 (2x)");
    }

    @Test
    void appliedFailRequestIsUnexpected() {
        var results = Checker.check(List.of(entry(0, FAIL)), Map.of("p0", 1));
        assertThat(result(results, "no-unexpected").offenders()).containsExactly("p0 (sent as fail)");
    }

    @Test
    void neverSentRowsAreUnexpectedInNaturalOrder() {
        var results = Checker.check(List.of(), Map.of("old-10", 1, "old-2", 1, "old-1", 1));
        assertThat(result(results, "no-unexpected").offenders())
            .containsExactly("old-1 (never sent)", "old-2 (never sent)", "old-10 (never sent)");
    }

    @Test
    void faultsFiredPassesOnlyWhenEveryPlannedFaultFired() {
        assertThat(Checker.faultsFired(3, 3).passed()).isTrue();
        assertThat(Checker.faultsFired(3, 2).offenders()).containsExactly("fired 2 of 3");
        assertThat(Checker.faultsFired(3, 4).offenders()).containsExactly("fired 4 of 3");
    }

    @Test
    void aRequestSentTwiceCountsOnceAndOkIfEitherCopyWasAccepted() {
        var now = Instant.now();
        var history = List.of(new HistoryEntry(0, "p0", FAIL, null, "refused", now, now), new HistoryEntry(1, "p0", OK, 202, null, now, now));

        assertThat(Checker.check(history, Map.of("p0", 1))).allMatch(CheckResult::passed);
        assertThat(result(Checker.check(history, Map.of("p0", 2)), "no-duplicates").offenders()).containsExactly("p0 (2x)");
        assertThat(result(Checker.check(history, Map.of()), "no-lost").offenders()).containsExactly("p0");
    }

    @Test
    void aRequestTheFaultDestroyedIsNotLost() {
        var results = Checker.check(List.of(entry(0, OK), entry(1, OK)), Map.of("p0", 1), Set.of("p1"));
        assertThat(result(results, "no-lost").passed()).isTrue();
    }

    @Test
    void perKeyOrderFlagsOnlyRequestsThatHappenedBeforeEachOther() {
        var t = Instant.parse("2026-10-31T10:00:00Z");
        var history = List.of(
            new HistoryEntry(0, "a1", OK, 202, null, t, t.plusMillis(5)),                       // acct-1, accepted at +5ms
            new HistoryEntry(1, "a2", OK, 202, null, t.plusMillis(100), t.plusMillis(105)),     // acct-1, sent after a1 was accepted
            new HistoryEntry(2, "b1", OK, 202, null, t.plusMillis(200), t.plusMillis(260)),     // acct-2
            new HistoryEntry(3, "b2", OK, 202, null, t.plusMillis(210), t.plusMillis(250)));    // acct-2, raced b1
        var keys = Map.of("a1", "acct-1", "a2", "acct-1", "b1", "acct-2", "b2", "acct-2");

        assertThat(Checker.perKeyOrder(history, keys, List.of("a1", "b2", "a2", "b1")).passed()).isTrue();   // racing pair may swap
        assertThat(Checker.perKeyOrder(history, keys, List.of("a2", "a1", "b1", "b2")).offenders())
            .containsExactly("acct-1: a2 was applied before a1, which happened first");
    }

    @Test
    void aRulesRowsAreItsOffenders() {
        assertThat(Checker.rule("one-row-per-payment", List.of("pay-1")).offenders()).containsExactly("pay-1");
        assertThat(Checker.rule("one-row-per-payment", List.of()).passed()).isTrue();
    }

    private static CheckResult result(List<CheckResult> results, String name) {
        return results.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static HistoryEntry entry(int index, HistoryEntry.Status status) {
        var now = Instant.now();
        return new HistoryEntry(index, "p" + index, status, status == OK ? 202 : null, null, now, now);
    }
}
