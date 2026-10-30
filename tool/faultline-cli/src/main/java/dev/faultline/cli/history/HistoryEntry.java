package dev.faultline.cli.history;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultline.cli.workload.Payment;

/** What happened to one payment the generator sent: one line of history.jsonl. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HistoryEntry(
    int index,
    String paymentId,
    String accountId,
    long amountMinor,
    Status status,
    Integer http,       // HTTP status code, null if no response came back
    String error,       // why there was no response, null otherwise
    Instant sentAt,
    Instant doneAt) {

    public enum Status {
        /** The app accepted it: the payment definitely entered the system. */
        @JsonProperty("ok") OK,
        /** It definitely never entered the system (connection refused, or rejected with a 4xx). */
        @JsonProperty("fail") FAIL,
        /** Unknown: a timeout or a 5xx, where the message may already be in Kafka. */
        @JsonProperty("info") INFO
    }

    public static HistoryEntry response(Payment p, Status status, int http, Instant sentAt, Instant doneAt) {
        return new HistoryEntry(p.index(), p.paymentId(), p.accountId(), p.amountMinor(), status, http, null, sentAt, doneAt);
    }

    public static HistoryEntry noResponse(Payment p, Status status, String error, Instant sentAt, Instant doneAt) {
        return new HistoryEntry(p.index(), p.paymentId(), p.accountId(), p.amountMinor(), status, null, error, sentAt, doneAt);
    }
}
