package dev.faultline.cli.run;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.INFO;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.plan.Plan;

class CorrelationTest {
    private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");

    private static HistoryEntry answered(int index, String id, String effect) {
        return new HistoryEntry(index, id, OK, 201, null, T, T, effect);
    }

    @Test
    void theAppsIdsAreTiedToTheRequestsTheyAnswered() {
        var history = List.of(answered(0, "k-0", "t-a"), answered(1, "k-1", "t-b"), answered(2, "k-1", "t-c"),
            new HistoryEntry(3, "k-3", INFO, null, "timeout", T, T), new HistoryEntry(4, "k-4", FAIL, 400, null, T, T));
        var ids = Correlation.of(new Plan.CorrelateBy("idempotencyKey", "transferId", null, "transfers", "id"), history);

        // a request submitted twice that made two rows was applied twice; the row of the unanswered k-3 is excused
        assertThat(ids.applied(Map.of("t-a", 1, "t-b", 1, "t-c", 1, "t-x", 1))).isEqualTo(Map.of("k-0", 1, "k-1", 2));
        assertThat(ids.unmatched()).isEqualTo(1);
        assertThat(ids.requests(Set.of("t-a", "t-z"))).containsExactlyInAnyOrder("k-0", "t-z");
        assertThat(ids.order(List.of("t-b", "t-a"))).containsExactly("k-1", "k-0");
        assertThat(ids.firstApplied(Map.of("t-b", T.plusSeconds(2), "t-c", T.plusSeconds(1)))).isEqualTo(Map.of("k-1", T.plusSeconds(1)));
    }

    @Test
    void moreUnknownRowsThanUnansweredRequestsWereNeverSent() {
        var ids = Correlation.of(new Plan.CorrelateBy("k", "id", null, "t", "id"), List.of(answered(0, "k-0", "t-a")));

        assertThat(ids.applied(Map.of("t-a", 1, "t-x", 1))).isEqualTo(Map.of("k-0", 1, "t-x", 1));
        assertThat(ids.unmatched()).isZero();
    }

    @Test
    void withoutAResponseFieldTheTableHoldsTheRequestsOwnIds() {
        var ids = Correlation.of(new Plan.CorrelateBy("paymentId", "payments", "payment_id"), List.of(answered(0, "p-0", null)));

        var applied = Map.of("p-0", 1, "p-9", 1);
        assertThat(ids.applied(applied)).isSameAs(applied);
    }
}
