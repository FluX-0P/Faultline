package dev.faultline.cli.traffic;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.INFO;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.faultline.cli.Json;
import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.history.HistoryLog;
import dev.faultline.cli.workload.Request;
import dev.faultline.cli.workload.Workload;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Sends requests on a fixed schedule, whether or not earlier ones have finished, the way real users keep
 * arriving when a server is slow. Each request runs on its own virtual thread.
 */
public final class TrafficSender implements AutoCloseable {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    static final String FORM = "application/x-www-form-urlencoded";
    private final URI target;
    private final Map<String, String> headers;
    private final boolean form;
    private final String responseField;
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    public TrafficSender(URI target) {
        this(target, Map.of());
    }

    /** @param headers sent on every request, such as Authorization; never written to the history */
    public TrafficSender(URI target, Map<String, String> headers) {
        this(target, headers, null, null);
    }

    /**
     * @param contentType application/x-www-form-urlencoded sends each JSON body as form fields; null or anything else as JSON
     * @param responseField the field of the app's JSON answer that carries the ID it made, recorded in the history; null: none
     */
    public TrafficSender(URI target, Map<String, String> headers, String contentType, String responseField) {
        this.target = target;
        this.headers = Map.copyOf(headers);
        this.form = FORM.equals(contentType);
        this.responseField = responseField;
    }

    /** Whether anything answers at the target. Any HTTP response, even a 404, counts. */
    public boolean isReachable() throws InterruptedException {
        try {
            http.send(HttpRequest.newBuilder(target).timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Fails fast if nothing is listening at the target. */
    public void checkReachable() throws InterruptedException {
        if (!isReachable()) throw new IllegalStateException("nothing is answering at " + target + " (is the app running?)");
    }

    /** Sends every request at {@code ratePerSecond}, records each outcome, and returns once all have finished. */
    public Duration send(List<Request> requests, String method, String path, int ratePerSecond, HistoryLog history)
            throws InterruptedException {
        URI endpoint = target.resolve(path);
        long intervalNanos = 1_000_000_000L / ratePerSecond;
        long start = System.nanoTime();
        try (var inFlight = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests.size(); i++) {
                // due times come from the start, not the previous send, so a slow sleep never lowers the rate
                long wait = start + i * intervalNanos - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                Request r = requests.get(i);
                inFlight.submit(() -> history.append(sendOne(endpoint, method, r)));
            }
        } // closing the executor waits for every in-flight request
        return Duration.ofNanos(System.nanoTime() - start);
    }

    HistoryEntry sendOne(URI endpoint, String method, Request r) {
        var builder = HttpRequest.newBuilder(endpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", form ? FORM : "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(form ? formOf(r.body()) : r.body()));
        headers.forEach(builder::header);
        var request = builder.build();
        Instant sentAt = Instant.now();
        try {
            if (responseField == null) {
                int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                return HistoryEntry.response(r, classify(code), code, sentAt, Instant.now());
            }
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String effect = response.statusCode() / 100 == 2 ? idIn(response.body()) : null;
            return HistoryEntry.response(r, classify(response.statusCode()), response.statusCode(), sentAt, Instant.now(), effect);
        } catch (ConnectException | HttpConnectTimeoutException e) {
            // never reached the app, so it cannot have happened
            return HistoryEntry.noResponse(r, FAIL, describe(e), sentAt, Instant.now());
        } catch (IOException e) {
            // timed out or dropped after the request went out: it may or may not have happened
            return HistoryEntry.noResponse(r, INFO, describe(e), sentAt, Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HistoryEntry.noResponse(r, INFO, "interrupted", sentAt, Instant.now());
        }
    }

    /** The ID the app's JSON answer carries in the response field, or null when it carries none. */
    private String idIn(String body) {
        try {
            return Workload.field(Json.MAPPER.readTree(body), responseField).orElse(null);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** A JSON body as form fields: nested objects as a.b, arrays as a[0], each value as text. */
    static String formOf(String json) {
        var fields = new ArrayList<String>();
        flatten("", Json.MAPPER.readTree(json), fields);
        return String.join("&", fields);
    }

    private static void flatten(String name, JsonNode node, List<String> into) {
        if (node.isObject()) {
            node.properties().forEach(e -> flatten(name.isEmpty() ? e.getKey() : name + "." + e.getKey(), e.getValue(), into));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) flatten(name + "[" + i + "]", node.get(i), into);
        } else if (!node.isNull()) {
            into.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(node.asString(), StandardCharsets.UTF_8));
        }
    }

    static HistoryEntry.Status classify(int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 300) return OK;
        if (httpStatus >= 400 && httpStatus < 500) return FAIL;
        return INFO; // 5xx or anything unexpected: the message may already be in Kafka
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @Override
    public void close() {
        http.close();
    }
}
