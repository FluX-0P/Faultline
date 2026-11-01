package dev.faultline.cli.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.faultline.cli.plan.Hints;
import dev.faultline.cli.plan.Plan;
import tools.jackson.databind.JsonNode;

/**
 * How the app runs for a plan. From its jar, as one process, or as Docker Compose services, read from the Compose file
 * as {@code docker compose config} resolves it: the app's services (each running one module of the repo: built from it,
 * or running the same image), which of them consume from Kafka, where the HTTP API answers, and Faultline's helpers
 * next to them, the fake payment provider ({@code faultline stub}) and Toxiproxy. Faultline recreates the app's services
 * and the helpers for each run; the rest (the database, Kafka) stays up.
 *
 * @param compose null when the app runs from its jar
 * @param consumers the services that consume from Kafka, in name order; empty for a jar
 * @param stub the fake provider's address, when the app's services are pointed at it, else null
 * @param toxiproxy Toxiproxy's API address, else null
 * @param services the app's services with their modules; empty for a jar
 * @param faultTarget the service a code fault goes on (the first consumer), or none when the app is one process
 */
public record Deployment(Plan.Compose compose, String url, List<String> consumers, String stub, String toxiproxy,
                         List<Service> services, String faultTarget) {
    static final int STUB_PORT = 9090;
    static final int TOXIPROXY_PORT = 8474;
    static final int CONNECT_PORT = 8083;
    /** Kafka Connect images: Debezium's, Confluent's and the plain ones. */
    private static final Pattern CONNECT = Pattern.compile("(debezium/connect|cp-kafka-connect|kafka-connect|/connect:)");
    // COPY or ADD of a jar from a module's build output, such as COPY order/target/order.jar app.jar
    private static final Pattern COPY = Pattern.compile("(?im)^\\s*(?:COPY|ADD)\\s+(?:--\\S+\\s+)*(.+)$");

    /**
     * A service of the app: the module it runs (its folder from the repo's, "" for the app's own folder), the address on
     * this machine its HTTP port is published on (null if it is not), and whether it consumes from Kafka.
     */
    public record Service(String name, String module, String url, boolean consumer) {}

    /** A module as the deployment needs it: its folder from the repo's, its HTTP port in the container, whether it has Kafka listeners. */
    public record Module(String path, int port, boolean listens) {}

    public Deployment(Plan.Compose compose, String url, List<String> consumers, String stub, String toxiproxy) {
        this(compose, url, consumers, stub, toxiproxy, List.of(), consumers.isEmpty() ? null : consumers.getFirst());
    }

    public static Deployment jar(String url) {
        return new Deployment(null, url, List.of(), null, null);
    }

    /** An app of one module, in the app's folder. */
    public static Deployment compose(String file, String override, String profile, Path appDir, int serverPort) throws Exception {
        return compose(file, override, profile, appDir, List.of(new Module("", serverPort, true)), Hints.NONE);
    }

    /**
     * Resolves the Compose file with the docker CLI, so anchors, merges and profiles come out as Compose sees them.
     *
     * @param override Faultline's override from faultline doctor, read after the file, or null
     * @param hints the services to leave out, and the module of a service Faultline cannot tell from its build
     */
    public static Deployment compose(String file, String override, String profile, Path appDir, List<Module> modules, Hints hints) throws Exception {
        var args = new ArrayList<>(List.of("compose"));
        for (String f : Plan.Compose.appFiles(file)) args.addAll(List.of("-f", f));
        if (override != null) args.addAll(List.of("-f", override));
        if (profile != null) args.addAll(List.of("--profile", profile));
        args.addAll(List.of("config", "--format", "json"));
        return parse(DockerStack.docker(args), file, override, profile, appDir, modules, hints);
    }

    static Deployment parse(String config, String file, String profile, Path appDir, int serverPort) {
        return parse(config, file, null, profile, appDir, serverPort);
    }

    static Deployment parse(String config, String file, String override, String profile, Path appDir, int serverPort) {
        return parse(config, file, override, profile, appDir, List.of(new Module("", serverPort, true)), Hints.NONE);
    }

    /** @param config the output of {@code docker compose config --format json} */
    static Deployment parse(String config, String file, String override, String profile, Path appDir, List<Module> modules, Hints hints) {
        JsonNode services = ComposeConfig.jsonOf(config).path("services");
        Path app = appDir.toAbsolutePath().normalize();
        var moduleOf = new LinkedHashMap<String, String>();
        for (var e : services.properties()) {
            if (!hints.skipped().contains(e.getKey())) moduleOf(e.getKey(), e.getValue(), app, modules, hints).ifPresent(m -> moduleOf.put(e.getKey(), m));
        }
        // a service that runs the image one of them builds runs the same module
        var images = new HashMap<String, String>();
        moduleOf.forEach((name, m) -> {
            if (!image(services.path(name)).isEmpty()) images.putIfAbsent(image(services.path(name)), m);
        });
        for (var e : services.properties()) {
            if (!moduleOf.containsKey(e.getKey()) && !hints.skipped().contains(e.getKey()) && images.containsKey(image(e.getValue()))) {
                moduleOf.put(e.getKey(), images.get(image(e.getValue())));
            }
        }

        var appServices = new ArrayList<Service>();
        var consumers = new ArrayList<String>();
        var helpers = new ArrayList<String>();
        String stubService = null;
        String stub = null;
        String toxiproxy = null;
        String connect = null;
        for (var e : services.properties()) {
            String name = e.getKey();
            JsonNode s = e.getValue();
            if (hints.skipped().contains(name)) {
                continue;   // left out, such as a Connect off the journey whose connector a script registers by hand
            } else if (moduleOf.containsKey(name)) {
                Module module = modules.stream().filter(m -> m.path().equals(moduleOf.get(name))).findFirst().orElseThrow();
                boolean consumer = module.listens()
                    && !"false".equalsIgnoreCase(s.path("environment").path("SPRING_KAFKA_LISTENER_AUTO_STARTUP").asString(""));
                if (consumer) consumers.add(name);
                appServices.add(new Service(name, module.path(), published(s, module.port()).map(Deployment::local).orElse(null), consumer));
            } else if (command(s).stream().findFirst().filter("stub"::equals).isPresent()) {
                helpers.add(name);
                stubService = name;
                stub = published(s, option(command(s), "--port").map(Integer::parseInt).orElse(STUB_PORT)).map(Deployment::local).orElse(null);
            } else if (image(s).contains("toxiproxy")) {
                helpers.add(name);
                toxiproxy = published(s, TOXIPROXY_PORT).map(Deployment::local).orElse(null);
            } else if (CONNECT.matcher(image(s)).find()) {
                // Kafka Connect carries the app's events to Kafka (an outbox through Debezium): part of the journey
                helpers.add(name);
                connect = published(s, CONNECT_PORT).map(Deployment::local).orElse(null);
            }
        }
        if (appServices.isEmpty()) throw new IllegalStateException(file + " has no service built from " + appDir + ", or running its image");

        // the API: a service that takes HTTP but no Kafka messages, else any of the app's services, on its module's port
        String url = appServices.stream().filter(s -> s.url() != null).min(Comparator.comparing(Service::consumer)).map(Service::url)
            .orElseThrow(() -> new IllegalStateException("no service of " + appDir + " in " + file + " publishes its HTTP port"));

        // the stub only counts when the app is pointed at it, such as PAYMENTS_PROVIDER_URL: http://provider:9090
        if (stubService != null) {
            String host = "//" + stubService + ":";
            boolean used = appServices.stream().anyMatch(a -> services.path(a.name()).path("environment").properties().stream()
                .anyMatch(env -> env.getValue().asString("").contains(host)));
            if (!used) stub = null;
        }
        // a Redis the app's services point at holds state a run leaves behind, such as answers by idempotency key
        var flush = new ArrayList<String>();
        for (var e : services.properties()) {
            if (!image(e.getValue()).contains("redis")) continue;
            String name = e.getKey();
            boolean used = appServices.stream().anyMatch(a -> services.path(a.name()).path("environment").properties().stream()
                .map(env -> env.getValue().asString("")).anyMatch(v -> v.equals(name) || v.contains("//" + name + ":") || v.startsWith(name + ":")));
            if (used) flush.add(name);
        }
        var recreated = new ArrayList<>(helpers);
        appServices.forEach(a -> recreated.add(a.name()));
        return new Deployment(new Plan.Compose(file, profile, recreated, consumers.size(), override, connect, flush.isEmpty() ? null : List.copyOf(flush)),
            url, List.copyOf(consumers), stub, toxiproxy, List.copyOf(appServices), consumers.isEmpty() ? null : consumers.getFirst());
    }

    /**
     * The deployment as a plan for one journey sees it, in a repo of several modules: the HTTP API is a service of the
     * {@code entry} module, the consumers Faultline waits for are the {@code listener} module's, and a code fault goes on
     * a service of the {@code risky} module, a consumer first. An app of one module is the same for every journey.
     *
     * @throws IllegalStateException when no service of the entry module publishes its HTTP port
     */
    public Deployment forJourney(String entry, String listener, String risky) {
        if (compose == null || services.stream().map(Service::module).distinct().count() < 2) return this;
        String api = entry == null ? url : urlOf(entry)
            .orElseThrow(() -> new IllegalStateException("no service that runs " + entry + " publishes its HTTP port"));
        var listening = services.stream().filter(s -> s.module().equals(listener) && s.consumer()).map(Service::name).toList();
        String target = services.stream().filter(s -> s.module().equals(risky)).min(Comparator.comparing(s -> !s.consumer()))
            .map(Service::name).orElse(null);
        var c = new Plan.Compose(compose.file(), compose.profile(), compose.services(), listening.size(), compose.override(), compose.connect(),
            compose.flush());
        return new Deployment(c, api, listening, stub, toxiproxy, services, target);
    }

    /** Where a module's HTTP API answers: a service of it that publishes its port, one that consumes no Kafka messages first. */
    public Optional<String> urlOf(String module) {
        return services.stream().filter(s -> s.module().equals(module) && s.url() != null).min(Comparator.comparing(Service::consumer)).map(Service::url);
    }

    /** The services that run a module. */
    public List<String> servicesOf(String module) {
        return services.stream().filter(s -> s.module().equals(module)).map(Service::name).toList();
    }

    /**
     * The module a service runs, by its build, in this order: the build context is the module's folder, the Dockerfile
     * is in it, the Dockerfile copies the module's jar; else what the hints say.
     */
    static Optional<String> moduleOf(String name, JsonNode service, Path app, List<Module> modules, Hints hints) {
        String contextText = service.path("build").path("context").asString("");
        if (!contextText.isEmpty()) {
            Path context = Path.of(contextText).toAbsolutePath().normalize();
            Path dockerfile = context.resolve(service.path("build").path("dockerfile").asString("Dockerfile")).normalize();
            for (var m : modules) if (context.equals(app.resolve(m.path()).normalize())) return Optional.of(m.path());
            for (var m : modules) if (dockerfile.getParent().equals(app.resolve(m.path()).normalize())) return Optional.of(m.path());
            if (context.startsWith(app) && Files.isRegularFile(dockerfile)) {
                var copied = copiedFrom(dockerfile, context);
                for (var m : modules) {
                    Path dir = app.resolve(m.path()).normalize();
                    if (!m.path().isEmpty() && copied.stream().anyMatch(p -> p.startsWith(dir.resolve("target")) || p.startsWith(dir.resolve("build")))) {
                        return Optional.of(m.path());
                    }
                }
            }
        }
        String hinted = hints.serviceModules().get(name);
        if (hinted == null) return Optional.empty();
        if (modules.stream().noneMatch(m -> m.path().equals(hinted))) {
            throw new IllegalArgumentException(Hints.FILE + ": services says " + name + " runs " + hinted + ", which is no Spring Boot app of the repo");
        }
        return Optional.of(hinted);
    }

    /** The files a Dockerfile's COPY and ADD lines take from the build context (every source, not the destination). */
    static List<Path> copiedFrom(Path dockerfile, Path context) {
        String text;
        try {
            text = Files.readString(dockerfile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + dockerfile, e);
        }
        var sources = new ArrayList<Path>();
        for (var m : COPY.matcher(text).results().toList()) {
            var parts = m.group(1).strip().split("\\s+");
            for (int i = 0; i < parts.length - 1; i++) {
                if (!parts[i].contains("://")) sources.add(context.resolve(parts[i].replace("*", "x")).normalize());
            }
        }
        return sources;
    }

    private static String image(JsonNode service) {
        return service.path("image").asString("");
    }

    private static List<String> command(JsonNode service) {
        var command = new ArrayList<String>();
        service.path("command").forEach(c -> command.add(c.asString("")));
        return command;
    }

    private static Optional<String> option(List<String> command, String name) {
        int i = command.indexOf(name);
        return i >= 0 && i + 1 < command.size() ? Optional.of(command.get(i + 1)) : Optional.empty();
    }

    /**
     * The port on this machine that a container port is published on; for a range, such as 51080-51081 for a service run
     * as two replicas, its first port.
     */
    private static Optional<Integer> published(JsonNode service, int target) {
        for (JsonNode p : service.path("ports")) {
            String published = p.path("published").asString("");
            if (p.path("target").asInt(-1) == target && published.matches("\\d+(-\\d+)?")) {
                return Optional.of(Integer.parseInt(published.split("-")[0]));
            }
        }
        return Optional.empty();
    }

    private static String local(int port) {
        return "http://localhost:" + port;
    }
}
