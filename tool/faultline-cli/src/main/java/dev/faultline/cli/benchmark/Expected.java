package dev.faultline.cli.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.faultline.cli.benchmark.BenchmarkCommand.Scores;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * benchmark/expected.yaml: the scores the benchmark must reach, for the hand-written plans, the AI plans and the scan.
 * A floor for what must be confirmed, planned and flagged; a ceiling for false confirmations and false flags. A score
 * the file names but the benchmark did not measure falls short too, so a skipped part cannot pass by being skipped.
 */
record Expected(Runs handWritten, Runs ai, Scan scan) {
    private static final YAMLMapper YAML = YAMLMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /** {@code planned} is for the AI plans only: the bugs Faultline drew up a plan for. */
    record Runs(Integer planned, Integer confirmed, Integer falseConfirmations) {}

    record Scan(Integer flagged, Integer falseFlags) {}

    static Expected read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("no expected scores at " + file);
        try {
            return YAML.readValue(file.toFile(), Expected.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + e.getOriginalMessage());
        }
    }

    /** What falls short of this file, in words; empty when every score reaches it. */
    List<String> shortfalls(Scores scores, boolean aiPlans) {
        var shortfalls = new ArrayList<String>();
        Runs runs = aiPlans ? ai : handWritten;
        String plans = aiPlans ? "AI plans" : "hand-written plans";
        if (runs != null) {
            if (aiPlans) atLeast(shortfalls, plans + " drawn up", scores.planned(), runs.planned(), scores.bugs());
            atLeast(shortfalls, plans + " confirmed", scores.confirmed(), runs.confirmed(), scores.bugs());
            atMost(shortfalls, plans + " false confirmations on the bug-free build", scores.falseConfirmations(), runs.falseConfirmations());
        }
        if (scan != null) {
            atLeast(shortfalls, "scan flagged", scores.scanFlagged(), scan.flagged(), scores.bugsWithPattern());
            atMost(shortfalls, "scan false flags on the bug-free build", scores.scanFalseFlags(), scan.falseFlags());
        }
        return shortfalls;
    }

    private static void atLeast(List<String> shortfalls, String what, Integer measured, Integer floor, int of) {
        if (floor == null) return;
        if (measured == null) shortfalls.add("%s: not measured, expected at least %d".formatted(what, floor));
        else if (measured < floor) shortfalls.add("%s: %d of %d, expected at least %d".formatted(what, measured, of, floor));
    }

    private static void atMost(List<String> shortfalls, String what, Integer measured, Integer ceiling) {
        if (ceiling == null) return;
        if (measured == null) shortfalls.add("%s: not measured, expected at most %d".formatted(what, ceiling));
        else if (measured > ceiling) shortfalls.add("%s: %d, expected at most %d".formatted(what, measured, ceiling));
    }
}
