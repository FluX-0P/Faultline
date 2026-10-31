package dev.faultline.cli.network;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.faultline.cli.Json;

/**
 * Toxiproxy's HTTP API (port 8474). Each proxy sits on one connection path, such as worker-1's link to Postgres;
 * Faultline breaks it by adding a toxic (latency, or a timeout that stops the replies) or by switching it off, which
 * cuts every connection and refuses new ones, and undoes that when the fault ends.
 */
public final class Toxiproxy implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final URI api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public Toxiproxy(String api) {
        this.api = URI.create(api.endsWith("/") ? api : api + "/");
    }

    /** Takes every toxic off every proxy and switches them all on: a clean start whatever an earlier run left behind. */
    public void reset() throws IOException, InterruptedException {
        send("POST", "reset", null);
    }

    /** Throws unless Toxiproxy answers and has this proxy. */
    public void checkProxy(String proxy) throws IOException, InterruptedException {
        var response = request("GET", "proxies/" + proxy, null);
        if (response.statusCode() == 404) {
            throw new IllegalStateException("Toxiproxy at " + api + " has no proxy named " + proxy + "; it has: " + request("GET", "proxies", null).body());
        }
        if (response.statusCode() >= 300) throw new IllegalStateException("Toxiproxy answered " + response.statusCode() + ": " + response.body());
    }

    /** Delays every reply on the proxy by {@code latency}. */
    public void addLatency(String proxy, String name, Duration latency) throws IOException, InterruptedException {
        addToxic(proxy, name, "latency", Map.of("latency", latency.toMillis(), "jitter", 0));
    }

    /** Stops every reply on the proxy until the toxic is removed; connections stay open and hang. */
    public void addTimeout(String proxy, String name) throws IOException, InterruptedException {
        addToxic(proxy, name, "timeout", Map.of("timeout", 0));
    }

    public void removeToxic(String proxy, String name) throws IOException, InterruptedException {
        send("DELETE", "proxies/" + proxy + "/toxics/" + name, null);
    }

    /** Off closes every connection through the proxy and refuses new ones; on lets them through again. */
    public void enable(String proxy, boolean enabled) throws IOException, InterruptedException {
        send("POST", "proxies/" + proxy, Map.of("enabled", enabled));
    }

    // replies come downstream: the app's requests still arrive, so a query or commit can succeed while the app never hears
    private void addToxic(String proxy, String name, String type, Map<String, Object> attributes) throws IOException, InterruptedException {
        var toxic = new LinkedHashMap<String, Object>();
        toxic.put("name", name);
        toxic.put("type", type);
        toxic.put("stream", "downstream");
        toxic.put("toxicity", 1.0);
        toxic.put("attributes", attributes);
        send("POST", "proxies/" + proxy + "/toxics", toxic);
    }

    private void send(String method, String path, Object body) throws IOException, InterruptedException {
        var response = request(method, path, body);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Toxiproxy " + method + " /" + path + " answered " + response.statusCode() + ": " + response.body());
        }
    }

    private HttpResponse<String> request(String method, String path, Object body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(api.resolve(path)).timeout(TIMEOUT)
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
