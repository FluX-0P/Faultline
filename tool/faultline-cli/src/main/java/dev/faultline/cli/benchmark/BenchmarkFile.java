package dev.faultline.cli.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.Workspace;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * benchmark/bugs.yaml: the demo app's planted bugs. For each, the settings that switch it on, the hand-written plan that
 * should confirm it, and (when it has one) the scan pattern and location that should flag it. {@code bugFree} switches
 * every bug off.
 */
public record BenchmarkFile(String app, String appDir, Map<String, String> bugFree, List<Bug> bugs) {
    private static final YAMLMapper YAML = YAMLMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public record Bug(String id, String title, String pattern, String location, String check, String plan, Map<String, String> settings) {}

    public static BenchmarkFile read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("no benchmark at " + file);
        try {
            return YAML.readValue(file.toFile(), BenchmarkFile.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + e.getOriginalMessage());
        }
    }

    /** The app settings for a run: every bug off, then {@code bug}'s own settings on top (none for the bug-free build). */
    public List<String> args(Bug bug) {
        var settings = new LinkedHashMap<>(bugFree);
        if (bug != null) settings.putAll(bug.settings());
        var args = new ArrayList<String>();
        settings.forEach((k, v) -> args.add("--" + k + "=" + v));
        return args;
    }

    /** Where faultline scan wrote its risks for this app, if it has run. */
    public Path risksFile() {
        return Workspace.forApp(Path.of(appDir)).resolve(Workspace.RISKS);
    }
}
