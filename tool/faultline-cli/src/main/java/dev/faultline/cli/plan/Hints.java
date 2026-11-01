package dev.faultline.cli.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import dev.faultline.cli.Out;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * faultline/hints.yaml in the app's folder: what Faultline cannot read from the code, written by whoever onboards the
 * app, and the only thing added to it. {@code auth} goes on every request, such as
 * {@code {header: Authorization, value: "Bearer ${env:SHOP_TOKEN}"}}; {@code seed} is a SQL file next to it (or several,
 * comma-separated, each for the database it writes to) that loads
 * test data after every reset. faultline plan copies both into the plan. {@code kafka.bootstrap} is where Faultline
 * reaches the app's Kafka from this machine, for a broker faultline doctor cannot give a listener of its own.
 * {@code images} gives a helper service of the stack (never one of the app's) another image, by service name, in
 * Faultline's Compose override: for an image today's Docker can no longer pull, such as one with an old v1 manifest.
 * {@code skip} names services Faultline leaves out, such as one of the app that does not start under the repo's own
 * Compose file, so it can test the rest, or a helper off the journeys it tests, such as a Kafka Connect whose connector
 * a script registers by hand; doctor says so every time. {@code services} names the module a service runs,
 * by its folder in the repo, when Faultline cannot tell from its build, such as {@code {account: services/account}}.
 * {@code builds} gives a service of the app that runs a prebuilt image the Dockerfile that makes it, by its path in the
 * repo, such as {@code {order-service: order-service/Dockerfile.svc}}: Faultline's override builds it there, in the
 * Dockerfile's folder, as the repo's own build script does, so the code that runs is the code Faultline reads.
 * {@code ports} publishes a service on other host ports than its Compose file does, such as
 * {@code {cart-db: ["15432:5432"]}}, for a port this machine cannot publish (Windows keeps ranges for itself): the
 * override replaces the service's published ports with these, and doctor says so every time.
 */
public record Hints(Plan.Auth auth, String seed, Kafka kafka, Map<String, String> images, List<String> skip, Map<String, String> services,
                    Map<String, String> builds, Map<String, List<String>> ports) {
    public static final String FILE = "faultline/hints.yaml";
    public static final Hints NONE = new Hints(null, null, null, null, null, null, null, null);
    private static final Pattern PORT = Pattern.compile("(?:[\\d.]+:)?\\d{1,5}:\\d{1,5}");

    public Hints(Plan.Auth auth, String seed, Kafka kafka, Map<String, String> images, List<String> skip, Map<String, String> services) {
        this(auth, seed, kafka, images, skip, services, null, null);
    }

    /** Where Faultline reaches the app's broker from this machine, such as localhost:29092. */
    public record Kafka(String bootstrap) {}
    private static final YAMLMapper YAML = YAMLMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /** The app's hints, or none when it has no hints file. */
    public static Hints read(Path appDir) throws IOException {
        Path file = appDir.resolve(FILE);
        if (!Files.isRegularFile(file)) return NONE;
        Hints hints;
        try {
            hints = YAML.readValue(file.toFile(), Hints.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("cannot read " + Out.path(file) + ": " + e.getOriginalMessage());
        }
        if (hints == null) return NONE;
        for (String seed : Plan.files(hints.seed())) {
            if (!Files.isRegularFile(file.resolveSibling(seed))) {
                throw new IllegalArgumentException(Out.path(file) + ": seed names " + seed + ", but there is no such file next to it");
            }
        }
        for (var e : hints.movedPorts().entrySet()) {
            for (String p : e.getValue()) {
                if (!PORT.matcher(p).matches()) {
                    throw new IllegalArgumentException(Out.path(file) + ": ports gives " + e.getKey() + " " + p + ", which is not HOST:CONTAINER, such as 15432:5432");
                }
            }
        }
        for (var e : hints.builds == null ? Map.<String, String>of().entrySet() : hints.builds.entrySet()) {
            if (!Files.isRegularFile(appDir.resolve(e.getValue()))) {
                throw new IllegalArgumentException(Out.path(file) + ": builds gives " + e.getKey() + " the Dockerfile " + e.getValue() + ", which is no file of the repo");
            }
        }
        return hints;
    }

    /** The host ports each named service is published on instead of its own, as HOST:CONTAINER; empty when there are none. */
    public Map<String, List<String>> movedPorts() {
        return ports == null ? Map.of() : ports;
    }

    /** The Dockerfile each named service is built from, as an absolute path; empty when there are none. */
    public Map<String, Path> dockerfiles(Path appDir) {
        var dockerfiles = new TreeMap<String, Path>();
        if (builds != null) builds.forEach((service, file) -> dockerfiles.put(service, appDir.resolve(file).toAbsolutePath().normalize()));
        return dockerfiles;
    }

    /** The images to give services instead of their own, by service name; empty when there are none. */
    public Map<String, String> imageReplacements() {
        return images == null ? Map.of() : images;
    }

    /** The services of the app to leave out; empty when there are none. */
    public List<String> skipped() {
        return skip == null ? List.of() : skip;
    }

    /** The module each named service runs, by its folder from the repo's; empty when there are none. */
    public Map<String, String> serviceModules() {
        return services == null ? Map.of() : services;
    }

    /**
     * The seed file as the plan names it: its path from the folder Faultline runs in. A repo of several databases may
     * have several, comma-separated, such as one per service's database: each loads into the database it writes to.
     */
    public String seedFile(Path appDir) {
        if (seed == null) return null;
        return String.join(",", Plan.files(seed).stream().map(s -> Out.path(appDir.resolve(FILE).resolveSibling(s).normalize())).toList());
    }
}
