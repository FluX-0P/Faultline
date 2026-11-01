package dev.faultline.cli.doctor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntPredicate;

import dev.faultline.cli.Out;
import dev.faultline.cli.app.ComposeConfig;

/**
 * How Faultline reaches the app's Kafka and databases from this machine, where the app's services reach them inside
 * Docker by names such as kafka:9092. A port the Compose file publishes is used as it is. Otherwise Faultline's Compose
 * override publishes the database's port on 127.0.0.1, and gives Kafka a listener of its own for this machine: a
 * published port alone is not enough there, since the broker tells clients to come back at the address it advertises.
 * The app's own services and settings stay as they are.
 */
final class Reach {
    static final String LISTENER = "FAULTLINE";
    static final int LISTENER_PORT = 39092;
    static final int FIRST_DATABASE_PORT = 15432;
    /** Images whose listeners Faultline knows how to add to, and the prefix of their listener settings. */
    private static final Map<String, String> KAFKA_IMAGES = Map.of("apache/kafka", "KAFKA_", "confluentinc/cp-kafka", "KAFKA_",
        "confluentinc/cp-server", "KAFKA_", "wurstmeister/kafka", "KAFKA_", "bitnami/kafka", "KAFKA_CFG_");
    private static final Set<String> PROTOCOLS = Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1");

    /** An address on this machine, and how Faultline got it. */
    record Address(String host, String how) {}

    private record Listener(String name, String host, int port) {}

    private final IntPredicate free;
    private final Set<Integer> taken = new TreeSet<>();
    private final Map<String, Map<String, Object>> override = new TreeMap<>();
    private final Map<String, Address> databases = new TreeMap<>();

    /** @param free whether a port on this machine is free: not in use, and not published by the stack */
    Reach(IntPredicate free) {
        this.free = free;
    }

    /** The database service's port on this machine: published by the Compose file, or by Faultline's override. */
    Address database(ComposeConfig.Service service, int port) {
        return databases.computeIfAbsent(service.name() + ":" + port, k -> {
            var published = service.published(port);
            if (published.isPresent()) return new Address("localhost:" + published.get(), "published by the Compose file");
            int hostPort = freePort(FIRST_DATABASE_PORT);
            ports(service.name()).add("127.0.0.1:" + hostPort + ":" + port);
            return new Address("localhost:" + hostPort, "published by Faultline's override");
        });
    }

    /**
     * Kafka on this machine: a listener the Compose file advertises as localhost and publishes on the same port, or one
     * the override adds. Empty for an image or a setup Faultline does not know how to change.
     */
    Optional<Address> kafka(ComposeConfig.Service service) {
        String prefix = KAFKA_IMAGES.entrySet().stream().filter(e -> service.image().contains(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(null);
        var env = service.environment();
        String p = prefix == null ? "KAFKA_" : prefix;
        var listeners = listeners(env.get(p + "LISTENERS"));
        var advertised = listeners(env.get(p + "ADVERTISED_LISTENERS"));
        for (var a : advertised) {
            if (!LOCAL.contains(a.host())) continue;
            int bind = listeners.stream().filter(l -> l.name().equals(a.name())).map(Listener::port).findFirst().orElse(a.port());
            if (service.published(bind).filter(hostPort -> hostPort == a.port()).isPresent()) {
                return Optional.of(new Address("localhost:" + a.port(), "its listener " + a.name() + " is published by the Compose file"));
            }
        }
        if (prefix == null) return Optional.empty();

        String listenersValue = env.get(p + "LISTENERS");
        String advertisedValue = env.get(p + "ADVERTISED_LISTENERS");
        String map = env.get(p + "LISTENER_SECURITY_PROTOCOL_MAP");
        var added = new LinkedHashMap<String, String>();
        if (advertisedValue == null && listenersValue == null && env.containsKey("KAFKA_ADVERTISED_HOST_NAME")) {
            // wurstmeister's older settings: one listener, named by its host and port
            String port = env.getOrDefault("KAFKA_PORT", "9092");
            listenersValue = "PLAINTEXT://:" + port;
            advertisedValue = "PLAINTEXT://" + env.get("KAFKA_ADVERTISED_HOST_NAME") + ":" + env.getOrDefault("KAFKA_ADVERTISED_PORT", port);
            listeners = listeners(listenersValue);
            added.put(p + "INTER_BROKER_LISTENER_NAME", "PLAINTEXT");
        } else if (advertisedValue != null && listenersValue == null && !env.getOrDefault(p + "PROCESS_ROLES", "").contains("controller")) {
            // without listeners, the broker binds each advertised one on every address
            listeners = advertised.stream().map(a -> new Listener(a.name(), "", a.port())).toList();
            listenersValue = String.join(",", listeners.stream().map(l -> l.name() + "://:" + l.port()).toList());
        } else if (advertisedValue == null || listenersValue == null) {
            return Optional.empty();
        }
        if (map == null) {
            if (!listeners.stream().allMatch(l -> PROTOCOLS.contains(l.name()))) return Optional.empty();
            map = String.join(",", listeners.stream().map(l -> l.name() + ":" + l.name()).toList());
        }
        var binds = listeners.stream().map(Listener::port).toList();
        int bind = LISTENER_PORT;
        while (binds.contains(bind)) bind++;
        int hostPort = freePort(LISTENER_PORT);
        added.put(p + "LISTENERS", listenersValue + "," + LISTENER + "://:" + bind);
        added.put(p + "ADVERTISED_LISTENERS", advertisedValue + "," + LISTENER + "://localhost:" + hostPort);
        added.put(p + "LISTENER_SECURITY_PROTOCOL_MAP", map + "," + LISTENER + ":PLAINTEXT");
        environment(service.name()).putAll(added);
        ports(service.name()).add("127.0.0.1:" + hostPort + ":" + bind);
        return Optional.of(new Address("localhost:" + hostPort, "a listener Faultline's override adds"));
    }

    /** The override also runs a helper service with another image, from the hints: one today's Docker can pull. */
    void replaceImage(String service, String image) {
        override.computeIfAbsent(service, k -> new LinkedHashMap<>()).put("image", image);
    }

    /**
     * The key Faultline's override replaces a service's published ports under: written out as {@code ports: !override},
     * since Compose otherwise adds an override's ports to the file's own.
     */
    static final String MOVED_PORTS = "ports-moved-by-faultline";

    /** The override also publishes a service on other host ports than its Compose file does, from the hints. */
    void movePorts(String service, List<String> ports) {
        override.computeIfAbsent(service, k -> new LinkedHashMap<>()).put(MOVED_PORTS, List.copyOf(ports));
    }

    /** The override as YAML for Compose, with moved ports tagged so they replace the service's own. */
    static String yaml(String written) {
        return written.replace(MOVED_PORTS + ":", "ports: !override");
    }

    /** The override also builds a service of the app from a Dockerfile of the repo, in the Dockerfile's folder, from the hints. */
    void build(String service, Path dockerfile) {
        override.computeIfAbsent(service, k -> new LinkedHashMap<>())
            .put("build", Map.of("context", Out.path(dockerfile.getParent()), "dockerfile", dockerfile.getFileName().toString()));
    }

    /** Faultline's Compose override: the services it changes, or empty when it needs none. */
    Map<String, Object> override() {
        return override.isEmpty() ? Map.of() : Map.of("services", override);
    }

    /** The host ports the override publishes. */
    Set<Integer> publishedPorts() {
        return Set.copyOf(taken);
    }

    private int freePort(int from) {
        int port = from;
        while (taken.contains(port) || !free.test(port)) port++;
        taken.add(port);
        return port;
    }

    @SuppressWarnings("unchecked")
    private List<String> ports(String service) {
        return (List<String>) override.computeIfAbsent(service, k -> new LinkedHashMap<>()).computeIfAbsent("ports", k -> new ArrayList<String>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> environment(String service) {
        return (Map<String, String>) override.computeIfAbsent(service, k -> new LinkedHashMap<>()).computeIfAbsent("environment", k -> new LinkedHashMap<String, String>());
    }

    /** "PLAINTEXT://kafka:9092,CONTROLLER://:9093" as its listeners, names in upper case. */
    private static List<Listener> listeners(String value) {
        var listeners = new ArrayList<Listener>();
        if (value == null) return listeners;
        for (String item : value.split(",")) {
            String s = item.strip();
            int scheme = s.indexOf("://");
            int colon = s.lastIndexOf(':');
            if (scheme < 0 || colon <= scheme) continue;
            try {
                listeners.add(new Listener(s.substring(0, scheme).toUpperCase(Locale.ROOT), s.substring(scheme + 3, colon), Integer.parseInt(s.substring(colon + 1))));
            } catch (NumberFormatException e) {
                // a listener without a port number: not one Faultline can use
            }
        }
        return listeners;
    }
}
