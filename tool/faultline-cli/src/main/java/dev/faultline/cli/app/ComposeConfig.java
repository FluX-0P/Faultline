package dev.faultline.cli.app;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import dev.faultline.cli.Json;
import tools.jackson.databind.JsonNode;

/**
 * A Compose file as {@code docker compose config --format json} resolves it, with anchors, merges and profiles applied
 * and links turned into dependencies: the project's name, and each service's image or build, published ports,
 * environment, the names other services reach it by, and the services it depends on.
 */
public record ComposeConfig(String project, Map<String, Service> services) {

    /** A container port, and the port on this machine it is published on (null when it is not published). */
    public record Port(int target, Integer published) {}

    /**
     * @param build the build context, or null for a service that runs an image
     * @param dockerfile the Dockerfile resolved against the context, or null
     * @param names what other services reach it by: its name, hostname, container name, network aliases and link aliases
     * @param containerName its fixed container name, or null when Compose names it
     */
    public record Service(String name, String image, Path build, Path dockerfile, List<Port> ports, Map<String, String> environment,
                          Set<String> names, Set<String> dependsOn, String containerName) {

        /** The port on this machine that a container port is published on. */
        public Optional<Integer> published(int target) {
            return ports.stream().filter(p -> p.target() == target && p.published() != null).map(Port::published).findFirst();
        }
    }

    /**
     * Resolves the files with the docker CLI. Without interpolation, values Compose would fill in from the shell or .env
     * stay as ${NAME}, which is how Faultline tells them apart from the file's own.
     */
    public static ComposeConfig read(List<String> files, String profile, boolean interpolate) throws Exception {
        var args = new ArrayList<String>(List.of("compose"));
        for (String f : files) args.addAll(List.of("-f", f));
        if (profile != null) args.addAll(List.of("--profile", profile));
        args.addAll(List.of("config", "--format", "json"));
        if (!interpolate) args.add("--no-interpolate");
        return parse(DockerStack.docker(args));
    }

    /** The JSON of {@code docker compose config --format json}, after any warnings Compose printed first, such as time="..." level=warning. */
    static JsonNode jsonOf(String output) {
        int start = output.startsWith("{") ? 0 : output.indexOf("\n{");
        if (start < 0) throw new IllegalStateException("docker compose config printed no JSON: " + output.strip());
        return Json.MAPPER.readTree(output.substring(start));
    }

    /** @param json the output of {@code docker compose config --format json}, after any warnings Compose printed first */
    public static ComposeConfig parse(String json) {
        JsonNode root = jsonOf(json);
        var aliases = new TreeMap<String, Set<String>>();
        for (var e : root.path("services").properties()) {
            for (JsonNode link : e.getValue().path("links")) {
                String[] parts = link.asString("").split(":", 2);
                if (parts.length == 2) aliases.computeIfAbsent(parts[0], k -> new TreeSet<>()).add(parts[1]);
            }
        }
        var services = new TreeMap<String, Service>();
        for (var e : root.path("services").properties()) {
            String name = e.getKey();
            JsonNode s = e.getValue();
            Path build = null;
            Path dockerfile = null;
            if (s.has("build")) {
                build = Path.of(s.path("build").path("context").asString(".")).toAbsolutePath().normalize();
                dockerfile = build.resolve(s.path("build").path("dockerfile").asString("Dockerfile")).normalize();
            }
            var ports = new ArrayList<Port>();
            for (JsonNode p : s.path("ports")) {
                String published = p.path("published").asString("");
                ports.add(new Port(p.path("target").asInt(-1), published.matches("\\d+") ? Integer.valueOf(published) : null));
            }
            var environment = new TreeMap<String, String>();
            for (var env : s.path("environment").properties()) {
                if (!env.getValue().isNull()) environment.put(env.getKey(), env.getValue().asString(""));
            }
            var names = new LinkedHashSet<String>(List.of(name));
            String hostname = s.path("hostname").asString("");
            if (!hostname.isEmpty()) names.add(hostname);
            String containerName = s.path("container_name").asString("");
            if (!containerName.isEmpty()) names.add(containerName);
            for (var network : s.path("networks").properties()) {
                for (JsonNode alias : network.getValue().path("aliases")) names.add(alias.asString(""));
            }
            names.addAll(aliases.getOrDefault(name, Set.of()));
            var dependsOn = new TreeSet<String>();
            s.path("depends_on").properties().forEach(d -> dependsOn.add(d.getKey()));
            services.put(name, new Service(name, s.path("image").asString(""), build, dockerfile, List.copyOf(ports), environment,
                Set.copyOf(names), dependsOn, containerName.isEmpty() ? null : containerName));
        }
        return new ComposeConfig(root.path("name").asString(""), services);
    }

    /** This config with some services running other images, as Faultline's override makes them run. */
    public ComposeConfig withImages(Map<String, String> images) {
        if (images.isEmpty()) return this;
        var changed = new TreeMap<>(services);
        images.forEach((name, image) -> changed.computeIfPresent(name, (n, s) -> new Service(n, image, null, null, s.ports(), s.environment(),
            s.names(), s.dependsOn(), s.containerName())));
        return new ComposeConfig(project, changed);
    }

    /** The service other containers reach at this host name. */
    public Optional<Service> reachedAt(String host) {
        return services.values().stream().filter(s -> s.names().contains(host)).findFirst();
    }

    /** These services and every service they depend on, directly or not: what {@code docker compose up} starts for them. */
    public Set<String> withDependencies(Collection<String> names) {
        var all = new TreeSet<String>();
        var todo = new ArrayDeque<>(names);
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!all.add(name)) continue;
            var service = services.get(name);
            if (service != null) todo.addAll(service.dependsOn());
        }
        return all;
    }

    /** Each service's environment, keyed by service name; what {@link #read} gives without interpolation. */
    public Map<String, Map<String, String>> environments() {
        var environments = new LinkedHashMap<String, Map<String, String>>();
        services.forEach((name, s) -> environments.put(name, s.environment()));
        return environments;
    }
}
