package dev.faultline.cli.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.faultline.cli.Json;
import dev.faultline.cli.plan.Plan;
import tools.jackson.databind.JsonNode;

/**
 * How the app runs for a plan. From its jar, as one process, or as Docker Compose services, read from the Compose file
 * as {@code docker compose config} resolves it: the app's services (built from its folder, or running the same image),
 * which of them consume from Kafka, where the HTTP API answers, and Faultline's helpers next to them, the fake payment
 * provider ({@code faultline stub}) and Toxiproxy. Faultline recreates the app's services and the helpers for each run;
 * the rest (the database, Kafka) stays up.
 *
 * @param compose null when the app runs from its jar
 * @param consumers the services that consume from Kafka, in name order; empty for a jar
 * @param stub the fake provider's address, when the app's services are pointed at it, else null
 * @param toxiproxy Toxiproxy's API address, else null
 */
public record Deployment(Plan.Compose compose, String url, List<String> consumers, String stub, String toxiproxy) {
    static final int STUB_PORT = 9090;
    static final int TOXIPROXY_PORT = 8474;

    public static Deployment jar(String url) {
        return new Deployment(null, url, List.of(), null, null);
    }

    /**
     * Resolves the Compose file with the docker CLI, so anchors, merges and profiles come out as Compose sees them.
     *
     * @param override Faultline's override from faultline doctor, read after the file, or null
     */
    public static Deployment compose(String file, String override, String profile, Path appDir, int serverPort) throws Exception {
        var args = new ArrayList<>(List.of("compose", "-f", file));
        if (override != null) args.addAll(List.of("-f", override));
        if (profile != null) args.addAll(List.of("--profile", profile));
        args.addAll(List.of("config", "--format", "json"));
        return parse(DockerStack.docker(args), file, override, profile, appDir, serverPort);
    }

    static Deployment parse(String config, String file, String profile, Path appDir, int serverPort) {
        return parse(config, file, null, profile, appDir, serverPort);
    }

    /** @param config the output of {@code docker compose config --format json} */
    static Deployment parse(String config, String file, String override, String profile, Path appDir, int serverPort) {
        JsonNode services = Json.MAPPER.readTree(config).path("services");
        Path app = appDir.toAbsolutePath().normalize();
        var images = new ArrayList<String>();
        for (var e : services.properties()) {
            if (builtFrom(e.getValue(), app) && !image(e.getValue()).isEmpty()) images.add(image(e.getValue()));
        }
        var appServices = new ArrayList<String>();
        var consumers = new ArrayList<String>();
        var helpers = new ArrayList<String>();
        String stubService = null;
        String stub = null;
        String toxiproxy = null;
        for (var e : services.properties()) {
            String name = e.getKey();
            JsonNode s = e.getValue();
            if (builtFrom(s, app) || images.contains(image(s))) {
                appServices.add(name);
                if (!"false".equalsIgnoreCase(s.path("environment").path("SPRING_KAFKA_LISTENER_AUTO_STARTUP").asString(""))) consumers.add(name);
            } else if (command(s).stream().findFirst().filter("stub"::equals).isPresent()) {
                helpers.add(name);
                stubService = name;
                stub = published(s, option(command(s), "--port").map(Integer::parseInt).orElse(STUB_PORT)).map(Deployment::local).orElse(null);
            } else if (image(s).contains("toxiproxy")) {
                helpers.add(name);
                toxiproxy = published(s, TOXIPROXY_PORT).map(Deployment::local).orElse(null);
            }
        }
        if (appServices.isEmpty()) throw new IllegalStateException(file + " has no service built from " + appDir + ", or running its image");

        // the API: a service that takes HTTP but no Kafka messages, else any of the app's services, on the app's port
        String url = null;
        for (boolean apiOnly : new boolean[] {true, false}) {
            for (String name : appServices) {
                if (url == null && (!apiOnly || !consumers.contains(name))) url = published(services.path(name), serverPort).map(Deployment::local).orElse(null);
            }
        }
        if (url == null) throw new IllegalStateException("no service of " + appDir + " in " + file + " publishes port " + serverPort);

        // the stub only counts when the app is pointed at it, such as PAYMENTS_PROVIDER_URL: http://provider:9090
        if (stubService != null) {
            String host = "//" + stubService + ":";
            boolean used = appServices.stream().anyMatch(n -> services.path(n).path("environment").properties().stream()
                .anyMatch(env -> env.getValue().asString("").contains(host)));
            if (!used) stub = null;
        }
        var recreated = new ArrayList<>(helpers);
        recreated.addAll(appServices);
        return new Deployment(new Plan.Compose(file, profile, recreated, consumers.size(), override), url, List.copyOf(consumers), stub, toxiproxy);
    }

    /** The service a code fault goes on: the first consumer, or none when the app is one process. */
    public String faultTarget() {
        return consumers.isEmpty() ? null : consumers.getFirst();
    }

    private static boolean builtFrom(JsonNode service, Path app) {
        String context = service.path("build").path("context").asString("");
        return !context.isEmpty() && Path.of(context).toAbsolutePath().normalize().equals(app);
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

    /** The port on this machine that a container port is published on. */
    private static Optional<Integer> published(JsonNode service, int target) {
        for (JsonNode p : service.path("ports")) {
            if (p.path("target").asInt(-1) == target && !p.path("published").asString("").isEmpty()) {
                return Optional.of(Integer.parseInt(p.path("published").asString()));
            }
        }
        return Optional.empty();
    }

    private static String local(int port) {
        return "http://localhost:" + port;
    }
}
