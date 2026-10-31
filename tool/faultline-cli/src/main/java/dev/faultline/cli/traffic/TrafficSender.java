package dev.faultline.cli.traffic;

import static dev.faultline.cli.history.HistoryEntry.Status.FAIL;
import static dev.faultline.cli.history.HistoryEntry.Status.INFO;
import static dev.faultline.cli.history.HistoryEntry.Status.OK;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.history.HistoryLog;
import dev.faultline.cli.workload.Request;

/**
 * Sends requests on a fixed schedule, whether or not earlier ones have finished, the way real users keep
 * arriving when a server is slow. Each request runs on its own virtual thread.
 */
public final class TrafficSender implements AutoCloseable {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI target;
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    public TrafficSender(URI target) {
        this.target = target;
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
        var request = HttpRequest.newBuilder(endpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(r.body()))
            .build();
        Instant sentAt = Instant.now();
        try {
            int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return HistoryEntry.response(r, classify(code), code, sentAt, Instant.now());
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
