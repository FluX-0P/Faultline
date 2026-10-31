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
    void anAppliedRequestWithoutAnEventDownstreamIsLostThere() {
        var applied = Map.of("p0", 1, "p1", 1, "p10", 1, "p2", 1);

        var result = Checker.noLostDownstream(applied, Set.of("p0", "p1", "p99"), "payments.completed");

        assertThat(result.offenders()).containsExactly("p2 (applied, but no event on payments.completed)",
            "p10 (applied, but no event on payments.completed)");
        assertThat(Checker.noLostDownstream(applied, Set.of("p0", "p1", "p2", "p10"), "payments.completed").passed()).isTrue();
    }

    @Test
    void recoveryWithinMeasuresHowSoonEverythingSentBeforeTheEndWasApplied() {
        var end = Instant.parse("2026-10-31T10:00:10Z");
        var history = List.of(
            new HistoryEntry(0, "p0", OK, 202, null, end.minusSeconds(8), end.minusSeconds(8)),
            new HistoryEntry(1, "p1", OK, 202, null, end.minusSeconds(1), end.minusSeconds(1)),
            new HistoryEntry(2, "p2", FAIL, null, "refused", end.minusSeconds(1), end.minusSeconds(1)),   // never entered: not waited for
            new HistoryEntry(3, "p3", OK, 202, null, end.plusSeconds(1), end.plusSeconds(1)));             // sent after the end
        var budget = java.time.Duration.ofSeconds(30);

        var caughtUp = Checker.recoveryWithin(history, end, budget,
            Map.of("p0", end.minusSeconds(7), "p1", end.plusMillis(2_500), "p3", end.plusSeconds(90)));
        assertThat(caughtUp.passed()).isTrue();
        assertThat(caughtUp.note()).isEqualTo("caught up 2.5s after the fault ended (allowed 30s)");

        var stuck = Checker.recoveryWithin(history, end, budget, Map.of("p1", end.plusSeconds(45)));
        assertThat(stuck.offenders()).containsExactly("p0 (never applied)", "p1 (applied 45.0s after the fault ended)");
        assertThat(stuck.note()).isEqualTo("2 requests sent before the fault ended were not applied within 30s of its end");

        assertThat(Checker.recoveryWithin(history, null, budget, Map.of()).passed()).isFalse();
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
