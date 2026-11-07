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
 * every bug off; {@code compose} is how the app runs for the plans Faultline draws up itself. {@code appMap} and
 * {@code llm} are what the scan and the AI plans were scored with, so they score the same on any machine.
 */
public record BenchmarkFile(String app, String appDir, ComposeFile compose, String appMap, LlmAnswers llm, Map<String, String> bugFree,
                            List<Bug> bugs) {
    private static final YAMLMapper YAML = YAMLMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public record Bug(String id, String title, String pattern, String location, String check, String plan, Map<String, String> settings) {}

    /**
     * The LLM answers the scores were measured with: the model, when FAULTLINE_LLM_MODEL is not set, and the folder of
     * answers, next to this file. A question the folder holds needs no LLM key; one it lacks goes to the LLM.
     */
    public record LlmAnswers(String model, String cache) {}

    /** The Compose file the app's services are in, and their profile (may be null). */
    public record ComposeFile(String file, String profile) {}

    public static BenchmarkFile read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("no benchmark at " + file);
        try {
            return YAML.readValue(file.toFile(), BenchmarkFile.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + e.getOriginalMessage());
        }
    }

    /** The app's settings: every bug off, then {@code bug}'s own settings on top (none for the bug-free build). */
    public Map<String, String> settings(Bug bug) {
        var settings = new LinkedHashMap<>(bugFree);
        if (bug != null) settings.putAll(bug.settings());
        return settings;
    }

    /** The same settings as app arguments for a run, such as --payments.dedupe=none. */
    public List<String> args(Bug bug) {
        var args = new ArrayList<String>();
        settings(bug).forEach((k, v) -> args.add("--" + k + "=" + v));
        return args;
    }

    /** The app map the scan and the AI plans read: this file's {@code app_map}, else where faultline understand wrote it. */
    public Path appMapFile(Path benchmarkFile) {
        return appMap != null ? benchmarkFile.resolveSibling(appMap) : Workspace.forApp(Path.of(appDir)).resolve(Workspace.APP_MAP);
    }

    /** The folder of LLM answers the scores were measured with, or null. */
    public Path llmCache(Path benchmarkFile) {
        return llm == null || llm.cache() == null ? null : benchmarkFile.resolveSibling(llm.cache());
    }
}
