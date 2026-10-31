package dev.faultline.cli.source;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The app's base Spring configuration (application.yml, .yaml or .properties) as flat keys such as
 * spring.datasource.url. Profiles are ignored. Flattening also drops comments, so the LLM sees settings, not notes.
 */
public final class SpringConfig {
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    // ${NAME:default} -> default; Faultline does not know the app's environment
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^:}]+:([^}]*)}");

    private final Map<String, String> values;

    SpringConfig(Map<String, String> values) {
        this.values = values;
    }

    public static SpringConfig load(RepoFiles repo) {
        var values = new TreeMap<String, String>();
        for (var file : repo.springConfigs()) {
            values.putAll(file.path().endsWith(".properties") ? flattenProperties(file.text()) : flattenYaml(file.text()));
        }
        return new SpringConfig(values);
    }

    /** This configuration with some settings changed, as a deployment or a benchmark run would set them. */
    public SpringConfig with(Map<String, String> settings) {
        var values = new TreeMap<>(this.values);
        values.putAll(settings);
        return new SpringConfig(values);
    }

    public Optional<String> get(String key) {
        String value = values.get(key);
        if (value == null) return Optional.empty();
        var m = PLACEHOLDER.matcher(value);
        return Optional.of(m.matches() ? m.group(1) : value);
    }

    /** key=value lines, sorted. */
    public String asText() {
        return values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    static Map<String, String> flattenYaml(String yaml) {
        var values = new TreeMap<String, String>();
        JsonNode root = YAML.readTree(yaml);
        if (root != null) flatten("", root, values);
        return values;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> into) {
        if (node.isObject()) {
            for (var e : node.properties()) {
                // "[session.timeout.ms]" is Spring's way of keeping the dots in a map key
                String key = e.getKey().startsWith("[") || prefix.isEmpty() ? prefix + e.getKey() : prefix + "." + e.getKey();
                flatten(key, e.getValue(), into);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) flatten(prefix + "[" + i + "]", node.get(i), into);
        } else if (!node.isNull()) {
            into.put(prefix, node.asString());
        }
    }

    static Map<String, String> flattenProperties(String text) {
        var properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var values = new TreeMap<String, String>();
        properties.forEach((k, v) -> values.put(k.toString(), v.toString()));
        return values;
    }
}
