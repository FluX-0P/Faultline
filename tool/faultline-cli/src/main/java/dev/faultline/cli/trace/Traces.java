package dev.faultline.cli.trace;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.faultline.cli.app.DockerStack;

/**
 * Traces of a run, for the report to link each request it flags to: the OpenTelemetry Java agent runs next to
 * Faultline's in every service, and sends what each request does, in every service and through Kafka, to Grafana's
 * all-in-one observability container. Each request carries a W3C traceparent whose trace ID comes from its request ID,
 * so a request's trace is found by its ID alone. The container keeps running after the run, so the links keep working.
 */
public final class Traces {
    static final String AGENT_VERSION = "2.32.0";
    static final String AGENT_URL = "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/%s/opentelemetry-javaagent-%s.jar"
        .formatted(AGENT_VERSION, AGENT_VERSION);
    static final String IMAGE = "grafana/otel-lgtm:0.37.0";
    public static final String CONTAINER = "faultline-otel";
    /** Grafana on this machine; its own 3000 is often taken. */
    public static final int GRAFANA_PORT = 3300;
    static final int OTLP_PORT = 4318;
    public static final String AGENT_IN_CONTAINER = "/faultline/otel.jar";

    private final Path agentJar;

    private Traces(Path agentJar) {
        this.agentJar = agentJar;
    }

    /** The agent jar, downloaded once into {@code tools}, and the container started if it is not running yet. */
    public static Traces start(Path tools, Consumer<String> print) throws Exception {
        Path jar = tools.resolve("opentelemetry-javaagent-" + AGENT_VERSION + ".jar");
        if (!Files.isRegularFile(jar)) download(jar, print);
        var running = DockerStack.docker(List.of("ps", "-q", "--filter", "name=^" + CONTAINER + "$")).strip();
        if (running.isEmpty()) {
            try {
                DockerStack.docker(List.of("rm", "-f", CONTAINER));   // a stopped one from an earlier run
            } catch (IllegalStateException e) {
                // there was none
            }
            print.accept("traces    starting %s (%s): Grafana at http://localhost:%d".formatted(CONTAINER, IMAGE, GRAFANA_PORT));
            DockerStack.docker(List.of("run", "-d", "--name", CONTAINER, "-p", GRAFANA_PORT + ":3000", "-p", OTLP_PORT + ":4318", IMAGE));
        }
        return new Traces(jar);
    }

    /** The OpenTelemetry Java agent, from Maven Central, checked against the SHA-1 published next to it. */
    private static void download(Path jar, Consumer<String> print) throws Exception {
        print.accept("traces    downloading the OpenTelemetry Java agent %s".formatted(AGENT_VERSION));
        Files.createDirectories(jar.getParent());
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()) {
            Path part = jar.resolveSibling(jar.getFileName() + ".part");
            http.send(HttpRequest.newBuilder(URI.create(AGENT_URL)).build(), HttpResponse.BodyHandlers.ofFile(part));
            String expected = http.send(HttpRequest.newBuilder(URI.create(AGENT_URL + ".sha1")).build(), HttpResponse.BodyHandlers.ofString()).body().strip();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(part)));
            if (!actual.equalsIgnoreCase(expected.split("\\s+")[0])) {
                Files.deleteIfExists(part);
                throw new IOException("the OpenTelemetry agent from " + AGENT_URL + " does not match its SHA-1");
            }
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path agentJar() {
        return agentJar;
    }

    /** What a service needs to send its traces: its name, and where to send them (this machine's OTLP port). */
    public static Map<String, String> environment(String service) {
        var env = new LinkedHashMap<String, String>();
        env.put("OTEL_SERVICE_NAME", service);
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://host.docker.internal:" + OTLP_PORT);
        env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        env.put("OTEL_METRICS_EXPORTER", "none");
        env.put("OTEL_LOGS_EXPORTER", "none");
        return env;
    }

    /** The trace ID a request's spans share: 32 hex digits from its request ID. */
    public static String traceId(String requestId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(requestId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The W3C traceparent a request is sent with, sampled, so the app's spans continue its trace. */
    public static String traceparent(String requestId) {
        return "00-" + traceId(requestId) + "-" + traceId("span:" + requestId).substring(0, 16) + "-01";
    }

    /** Where Grafana shows a request's trace. */
    public static String link(String requestId) {
        String pane = """
            {"datasource":"tempo","queries":[{"refId":"A","datasource":{"type":"tempo","uid":"tempo"},"queryType":"traceql","query":"%s"}],"range":{"from":"now-24h","to":"now"}}"""
            .formatted(traceId(requestId));
        return "http://localhost:" + GRAFANA_PORT + "/explore?left=" + URLEncoder.encode(pane, StandardCharsets.UTF_8);
    }
}
