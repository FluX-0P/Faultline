package dev.faultline.cli.source;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The app's Spring configuration as flat keys such as spring.datasource.url: its application.yml, .yaml or .properties,
 * and, given the environment a service runs it with, what Spring Boot puts on top of them: the files and documents of the
 * active profiles, the environment variables through relaxed binding (SPRING_DATASOURCE_URL sets spring.datasource.url)
 * and SPRING_APPLICATION_JSON. Without an environment, only the base files count, and documents for a profile are left out.
 * Flattening also drops comments, so the LLM sees settings, not notes.
 */
public final class SpringConfig {
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    // ${NAME} or ${NAME:default}, anywhere in a value
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");
    private static final int MAX_DEPTH = 5;
    /** Keys with a dash that an environment variable can set without one being in the app's own files. */
    private static final List<String> KNOWN_KEYS = List.of("spring.kafka.bootstrap-servers", "spring.kafka.consumer.group-id",
        "spring.kafka.consumer.auto-offset-reset", "spring.kafka.consumer.enable-auto-commit", "spring.kafka.listener.ack-mode",
        "spring.kafka.listener.auto-startup", "spring.jpa.hibernate.ddl-auto", "spring.datasource.driver-class-name");
    private static final String APPLICATION_JSON = "SPRING_APPLICATION_JSON";
    /** No settings: what code outside every Spring Boot app of a repo runs with, as far as Faultline can tell. */
    public static final SpringConfig EMPTY = new SpringConfig(Map.of());

    private final Map<String, String> values;
    private final Map<String, String> environment;

    SpringConfig(Map<String, String> values) {
        this(values, Map.of());
    }

    private SpringConfig(Map<String, String> values, Map<String, String> environment) {
        this.values = values;
        this.environment = environment;
    }

    public static SpringConfig load(RepoFiles repo) {
        return load(repo, Map.of());
    }

    /** @param environment the environment variables of the service that runs the app, such as its Compose environment */
    public static SpringConfig load(RepoFiles repo, Map<String, String> environment) {
        var base = new TreeMap<String, String>();
        for (var file : repo.springConfigs()) base.putAll(flatten(file, null));
        var profiles = activeProfiles(base, environment);

        var values = new TreeMap<String, String>();
        for (var file : repo.springConfigs()) values.putAll(flatten(file, profiles));
        for (String profile : profiles) {
            for (var file : repo.springProfileConfigs(profile)) values.putAll(flatten(file, profiles));
        }
        bind(environment, values);
        String json = environment.get(APPLICATION_JSON);
        if (json != null && !json.isBlank()) flatten("", YAML.readTree(json), values);
        return new SpringConfig(values, Map.copyOf(environment));
    }

    /** This configuration with some settings changed, as a deployment or a benchmark run would set them. */
    public SpringConfig with(Map<String, String> settings) {
        var values = new TreeMap<>(this.values);
        values.putAll(settings);
        return new SpringConfig(values, environment);
    }

    /**
     * A setting with its ${NAME:default} placeholders resolved from the other settings and the environment, the way
     * Spring resolves them. A key the files do not set may still come straight from an environment variable (DB_HOST).
     */
    public Optional<String> get(String key) {
        String value = lookup(key);
        return value == null ? Optional.empty() : Optional.of(resolve(value, 0));
    }

    /** The first setting, by key, whose key matches, such as openapi.petstore.base-path; resolved like {@link #get}. */
    public Optional<String> find(Pattern key) {
        return values.keySet().stream().filter(k -> key.matcher(k).matches()).findFirst().flatMap(this::get);
    }

    /** The Spring profiles the environment or the base files switch on, in order. */
    public static List<String> activeProfiles(Map<String, String> base, Map<String, String> environment) {
        String active = environment.entrySet().stream().filter(e -> binds(e.getKey(), "spring.profiles.active"))
            .map(Map.Entry::getValue).findFirst().orElse(new SpringConfig(base, environment).get("spring.profiles.active").orElse(""));
        return Arrays.stream(active.split(",")).map(String::strip).filter(p -> !p.isEmpty()).distinct().toList();
    }

    private String lookup(String name) {
        String value = values.get(name);
        if (value != null) return value;
        value = environment.get(name);
        if (value != null) return value;
        return environment.entrySet().stream().filter(e -> binds(e.getKey(), name)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private String resolve(String value, int depth) {
        if (depth >= MAX_DEPTH || !value.contains("${")) return value;
        Matcher m = PLACEHOLDER.matcher(value);
        return m.replaceAll(match -> {
            String found = lookup(match.group(1));
            String replacement = found != null ? resolve(found, depth + 1) : match.group(2) != null ? match.group(2) : match.group();
            return Matcher.quoteReplacement(replacement);
        });
    }

    /** key=value lines, sorted, with each value's ${placeholders} resolved as {@link #get} does. */
    public String asResolvedText() {
        return values.keySet().stream().map(k -> k + "=" + get(k).orElse("")).collect(Collectors.joining("\n"));
    }

    /** key=value lines, sorted. */
    public String asText() {
        return values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Environment variables as settings: one that sets a key the app's files have (or a well-known one) sets it under
     * that name, the others become lower-case dotted keys, as Spring Boot's relaxed binding reads them.
     */
    static void bind(Map<String, String> environment, Map<String, String> values) {
        var keys = new TreeSet<>(values.keySet());
        keys.addAll(KNOWN_KEYS);
        for (var e : new TreeMap<>(environment).entrySet()) {
            if (e.getKey().equals(APPLICATION_JSON)) continue;
            String key = keys.stream().filter(k -> binds(e.getKey(), k)).findFirst()
                .orElse(e.getKey().toLowerCase(Locale.ROOT).replace('_', '.'));
            values.put(key, e.getValue());
        }
    }

    /**
     * Whether Spring binds the environment variable to the key: dots become underscores, dashes are dropped
     * (SPRING_KAFKA_BOOTSTRAPSERVERS) or, the older form, become underscores too (SPRING_KAFKA_BOOTSTRAP_SERVERS).
     */
    static boolean binds(String variable, String key) {
        if (key.contains("[")) return false;
        String upper = key.toUpperCase(Locale.ROOT).replace('.', '_');
        return variable.equals(upper.replace("-", "")) || variable.equals(upper.replace('-', '_'));
    }

    /**
     * A file's settings. Of a YAML file's documents, those for a profile count only when it is active; without
     * {@code profiles} (null) none of them do.
     */
    private static Map<String, String> flatten(RepoFiles.TextFile file, List<String> profiles) {
        if (file.path().endsWith(".properties")) return flattenProperties(file.text());
        var values = new TreeMap<String, String>();
        for (JsonNode document : documents(file.text())) {
            var settings = new TreeMap<String, String>();
            flatten("", document, settings);
            String onProfile = settings.containsKey("spring.config.activate.on-profile")
                ? settings.get("spring.config.activate.on-profile") : settings.get("spring.profiles");
            if (onProfile == null || profiles != null && activates(onProfile, profiles)) values.putAll(settings);
        }
        return values;
    }

    /** Whether a document's profile list, such as "dev, local" or "!prod", is switched on. */
    private static boolean activates(String onProfile, List<String> profiles) {
        Set<String> active = profiles.isEmpty() ? Set.of("default") : Set.copyOf(profiles);
        return Arrays.stream(onProfile.split(",")).map(String::strip)
            .anyMatch(p -> p.startsWith("!") ? !active.contains(p.substring(1)) : active.contains(p));
    }

    private static List<JsonNode> documents(String yaml) {
        var documents = new ArrayList<JsonNode>();
        try (MappingIterator<JsonNode> it = YAML.readerFor(JsonNode.class).readValues(yaml)) {
            while (it.hasNext()) {
                JsonNode document = it.next();
                if (document != null && !document.isNull() && !document.isMissingNode()) documents.add(document);
            }
        }
        return documents;
    }

    static Map<String, String> flattenYaml(String yaml) {
        var values = new TreeMap<String, String>();
        for (JsonNode document : documents(yaml)) flatten("", document, values);
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
