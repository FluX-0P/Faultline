package dev.faultline.cli.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Faultline's own settings, such as the LLM key. A real environment variable wins; otherwise the value comes from
 * the .env file in the folder Faultline runs from. That file is git-ignored, so keys never reach the repo.
 */
public final class Settings {
    public static final Path DOT_ENV = Path.of(".env");

    private final Map<String, String> env;
    private final Map<String, String> file;

    Settings(Map<String, String> env, Map<String, String> file) {
        this.env = env;
        this.file = file;
    }

    public static Settings load() {
        return load(DOT_ENV, System.getenv());
    }

    static Settings load(Path dotEnv, Map<String, String> env) {
        if (!Files.isRegularFile(dotEnv)) return new Settings(env, Map.of());
        try {
            return new Settings(env, parse(Files.readAllLines(dotEnv, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + dotEnv, e);
        }
    }

    public Optional<String> get(String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) value = file.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    public String require(String name, String hint) {
        return get(name).orElseThrow(() -> new IllegalStateException(name + " is not set: " + hint));
    }

    /** KEY=value lines. Blank lines and # comments are skipped, an "export " prefix and matching quotes are dropped. */
    static Map<String, String> parse(List<String> lines) {
        var values = new HashMap<String, String>();
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (s.startsWith("export ")) s = s.substring("export ".length()).strip();
            int eq = s.indexOf('=');
            if (eq < 1) continue;
            String value = s.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            } else if (value.contains(" #")) {
                value = value.substring(0, value.indexOf(" #")).strip();
            }
            values.put(s.substring(0, eq).strip(), value);
        }
        return values;
    }
}
