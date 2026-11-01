package dev.faultline.cli.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.Out;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * faultline/hints.yaml in the app's folder: what Faultline cannot read from the code, written by whoever onboards the
 * app, and the only thing added to it. {@code auth} goes on every request, such as
 * {@code {header: Authorization, value: "Bearer ${env:SHOP_TOKEN}"}}; {@code seed} is a SQL file next to it that loads
 * test data after every reset. faultline plan copies both into the plan. {@code kafka.bootstrap} is where Faultline
 * reaches the app's Kafka from this machine, for a broker faultline doctor cannot give a listener of its own.
 * {@code images} gives a helper service of the stack (never one of the app's) another image, by service name, in
 * Faultline's Compose override: for an image today's Docker can no longer pull, such as one with an old v1 manifest.
 * {@code skip} names services of the app Faultline leaves out, such as one that does not start under the repo's own
 * Compose file, so it can test the rest; doctor says so every time.
 */
public record Hints(Plan.Auth auth, String seed, Kafka kafka, Map<String, String> images, List<String> skip) {
    public static final String FILE = "faultline/hints.yaml";
    public static final Hints NONE = new Hints(null, null, null, null, null);

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
        if (hints.seed() != null && !Files.isRegularFile(file.resolveSibling(hints.seed()))) {
            throw new IllegalArgumentException(Out.path(file) + ": seed names " + hints.seed() + ", but there is no such file next to it");
        }
        return hints;
    }

    /** The images to give services instead of their own, by service name; empty when there are none. */
    public Map<String, String> imageReplacements() {
        return images == null ? Map.of() : images;
    }

    /** The services of the app to leave out; empty when there are none. */
    public List<String> skipped() {
        return skip == null ? List.of() : skip;
    }

    /** The seed file as the plan names it: its path from the folder Faultline runs in. */
    public String seedFile(Path appDir) {
        return seed == null ? null : Out.path(appDir.resolve(FILE).resolveSibling(seed).normalize());
    }
}
