package dev.faultline.cli.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import dev.faultline.cli.history.HistoryEntry.Status;
import dev.faultline.cli.workload.Payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryLogTest {

    @Test
    void entriesSurviveTheRoundTripSortedByIndex(@TempDir Path dir) throws Exception {
        var t = Instant.parse("2026-10-30T04:45:02.123Z");
        var ok = HistoryEntry.response(new Payment(1, "pay-42-1", "acct-49", 3479), Status.OK, 202, t, t.plusMillis(12));
        var info = HistoryEntry.noResponse(new Payment(0, "pay-42-0", "acct-31", 4111), Status.INFO, "HttpTimeoutException", t, t.plusSeconds(5));

        Path file = dir.resolve("history.jsonl");
        try (var log = HistoryLog.create(file)) {
            log.append(ok);   // completion order, not index order
            log.append(info);
        }

        assertThat(HistoryLog.read(file)).containsExactly(info, ok);
    }

    @Test
    void writesOneReadableJsonObjectPerLine(@TempDir Path dir) throws Exception {
        var t = Instant.parse("2026-10-30T04:45:02Z");
        Path file = dir.resolve("history.jsonl");
        try (var log = HistoryLog.create(file)) {
            log.append(HistoryEntry.response(new Payment(0, "pay-42-0", "acct-31", 4111), Status.OK, 202, t, t));
        }

        assertThat(Files.readAllLines(file)).singleElement().satisfies(line -> assertThat(line)
            .contains("\"status\":\"ok\"")
            .contains("\"sentAt\":\"2026-10-30T04:45:02Z\"")
            .doesNotContain("\"error\""));   // nulls are left out
    }
}
