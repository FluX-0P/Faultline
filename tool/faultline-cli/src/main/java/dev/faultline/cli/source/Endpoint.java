package dev.faultline.cli.source;

import java.util.List;

/**
 * A way into the app found in its code: an HTTP mapping (method and path) or a Kafka listener (topics and group).
 * {@code handler} is Class#method; {@code groupId} is null when the annotation leaves it to the configuration.
 */
public record Endpoint(Kind kind, String method, String path, List<String> topics, String groupId,
                       String handler, String file, int line) {

    public enum Kind { HTTP, KAFKA }

    public static Endpoint http(String method, String path, String handler, String file, int line) {
        return new Endpoint(Kind.HTTP, method, path, List.of(), null, handler, file, line);
    }

    public static Endpoint kafka(List<String> topics, String groupId, String handler, String file, int line) {
        return new Endpoint(Kind.KAFKA, null, null, List.copyOf(topics), groupId, handler, file, line);
    }

    /** POST /payments, or kafka payments.requested */
    public String name() {
        return kind == Kind.HTTP ? method + " " + path : "kafka " + String.join(",", topics);
    }
}
