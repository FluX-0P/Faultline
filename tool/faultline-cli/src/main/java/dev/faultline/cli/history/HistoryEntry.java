package dev.faultline.cli.history;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.faultline.cli.workload.Request;

/** What happened to one request the generator sent: one line of history.jsonl. {@code id} ties it to its effect. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HistoryEntry(
    int index,
    String id,
    Status status,
    Integer http,       // HTTP status code, null if no response came back
    String error,       // why there was no response, null otherwise
    Instant sentAt,
    Instant doneAt,
    String effect) {    // the ID the app's answer carries (correlate_by.response_field), null otherwise

    public HistoryEntry(int index, String id, Status status, Integer http, String error, Instant sentAt, Instant doneAt) {
        this(index, id, status, http, error, sentAt, doneAt, null);
    }

    public enum Status {
        /** The app accepted it: the request definitely entered the system. */
        @JsonProperty("ok") OK,
        /** It definitely never entered the system (connection refused, or rejected with a 4xx). */
        @JsonProperty("fail") FAIL,
        /** Unknown: a timeout or a 5xx, where the message may already be in Kafka. */
        @JsonProperty("info") INFO
    }

    public static HistoryEntry response(Request r, Status status, int http, Instant sentAt, Instant doneAt) {
        return response(r, status, http, sentAt, doneAt, null);
    }

    public static HistoryEntry response(Request r, Status status, int http, Instant sentAt, Instant doneAt, String effect) {
        return new HistoryEntry(r.index(), r.id(), status, http, null, sentAt, doneAt, effect);
    }

    public static HistoryEntry noResponse(Request r, Status status, String error, Instant sentAt, Instant doneAt) {
        return new HistoryEntry(r.index(), r.id(), status, null, error, sentAt, doneAt);
    }
}
