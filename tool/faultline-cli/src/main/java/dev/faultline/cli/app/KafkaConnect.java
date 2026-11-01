package dev.faultline.cli.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import dev.faultline.cli.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Kafka Connect's REST API, such as Debezium's: which connectors it runs. A stack whose events reach Kafka through a
 * connector is only ready once every connector, and every task of each, is RUNNING; before that the app's events wait
 * in its outbox table, and a run would look as if they were lost.
 */
public final class KafkaConnect implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final URI connectors;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public KafkaConnect(String url) {
        this.connectors = URI.create(url.replaceAll("/+$", "") + "/connectors?expand=status");
    }

    /** How many connectors run, all their tasks with them; 0 while Connect is not up or a connector is not running yet. */
    public int running() throws InterruptedException {
        try {
            var response = http.send(HttpRequest.newBuilder(connectors).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return 0;
            return running(Json.MAPPER.readTree(response.body()));
        } catch (IOException | JacksonException e) {
            return 0;
        }
    }

    /** @param status the answer to GET /connectors?expand=status: each connector's name with its status */
    static int running(JsonNode status) {
        int running = 0;
        for (var e : status.properties()) {
            JsonNode s = e.getValue().path("status");
            if (!"RUNNING".equals(s.path("connector").path("state").asString(""))) return 0;
            if (s.path("tasks").isEmpty()) return 0;
            for (JsonNode task : s.path("tasks")) if (!"RUNNING".equals(task.path("state").asString(""))) return 0;
            running++;
        }
        return running;
    }

    @Override
    public void close() {
        http.close();
    }
}
