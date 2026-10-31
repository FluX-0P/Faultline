package dev.faultline.cli.stub;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.Json;
import tools.jackson.core.type.TypeReference;

/** faultline run's side of the stub's control API: arm a fault, start and end an outage, read what fired. */
public final class StubControl implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public record Fired(String action, String paymentId, Instant at) {}

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public StubControl(String base) {
        this.base = URI.create(base.endsWith("/") ? base : base + "/");
    }

    public boolean isReachable() throws InterruptedException {
        try {
            return request("GET", "health", null).statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    /** No fault armed and no outage. */
    public void reset() throws IOException, InterruptedException {
        send("POST", "control/reset", Map.of());
    }

    public void arm(String action, long nthHit, int times, Duration delay) throws IOException, InterruptedException {
        send("POST", "control/fault", Map.of("action", action, "nthHit", nthHit, "times", times, "delayMs", delay.toMillis()));
    }

    public void outage(boolean down) throws IOException, InterruptedException {
        send("POST", "control/outage", Map.of("down", down));
    }

    /** Every armed fault that fired so far, oldest first. */
    public List<Fired> fired() throws IOException, InterruptedException {
        var response = request("GET", "control/fired", null);
        if (response.statusCode() != 200) throw new IllegalStateException("the stub answered " + response.statusCode() + ": " + response.body());
        return Json.MAPPER.readValue(response.body(), new TypeReference<List<Fired>>() {});
    }

    private void send(String method, String path, Object body) throws IOException, InterruptedException {
        var response = request(method, path, body);
        if (response.statusCode() >= 300) throw new IllegalStateException("the stub answered " + response.statusCode() + ": " + response.body());
    }

    private HttpResponse<String> request(String method, String path, Object body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT)
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
            .header("Content-Type", "application/json")
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        http.close();
    }
}
