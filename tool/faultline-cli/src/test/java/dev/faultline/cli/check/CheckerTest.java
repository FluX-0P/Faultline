package dev.faultline.cli.check;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.INFO;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    private static CheckResult result(List<CheckResult> results, String name) {
        return results.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static HistoryEntry entry(int index, HistoryEntry.Status status) {
        var now = Instant.now();
        return new HistoryEntry(index, "p" + index, status, status == OK ? 202 : null, null, now, now);
    }
}
