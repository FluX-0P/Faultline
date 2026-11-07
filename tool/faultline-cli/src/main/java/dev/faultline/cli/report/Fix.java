package dev.faultline.cli.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.faultline.cli.Json;
import tools.jackson.core.type.TypeReference;

/**
 * A fix the LLM suggested for a confirmed finding, from {@code faultline fix}: machine-made, so the report says so. A
 * person checks it, applies it, and runs the plan again to prove it.
 */
public record Fix(String riskId, String location, String model, String summary, String file, String change, String why, String risks) {
    public static final String FILE = "fixes.json";

    /** The run folder's suggested fixes; none when {@code faultline fix} has not run on it. */
    public static List<Fix> read(Path runDir) throws IOException {
        Path file = runDir.resolve(FILE);
        if (!Files.isRegularFile(file)) return List.of();
        return Json.MAPPER.readValue(file.toFile(), new TypeReference<List<Fix>>() {});
    }
}
