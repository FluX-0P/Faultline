package dev.faultline.cli.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.benchmark.BenchmarkCommand.BugResult;
import dev.faultline.cli.benchmark.BenchmarkCommand.Result;
import dev.faultline.cli.benchmark.BenchmarkCommand.Scores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** CI runs each bug in its own job, then scores their benchmark.json files as one and holds them to benchmark/expected.yaml. */
class BenchmarkMergeTest {
    private static final Path BENCHMARK = Path.of("../../benchmark/bugs.yaml");
    private static final Path EXPECTED = Path.of("../../benchmark/expected.yaml");

    private static BugResult bug(String id, String pattern, String withBug, String bugFree) {
        return new BugResult(id, "title " + id, pattern, "no-lost", pattern == null ? null : true, "benchmark/" + id.toLowerCase() + ".plan.yaml", null,
            "hand-written", withBug, "why", "runs/x/" + id, bugFree, "why", "runs/y/" + id);
    }

    /** One job's benchmark.json, for one bug: its scan of the bug-free build flagged {@code falseFlags}. */
    private static Path job(Path dir, BugResult bug, int falseFlags) {
        Path file = dir.resolve(bug.id() + ".json");
        int confirmed = "confirmed".equals(bug.withBug()) ? 1 : 0;
        Json.writePretty(file, new Result(Instant.now(), 42, "hand-written", List.of(bug),
            new Scores(1, confirmed, 0, bug.pattern() == null ? 0 : 1, bug.pattern() == null ? 0 : 1, falseFlags, null)));
        return file;
    }

    private record Outcome(int exit, String out) {}

    private static Outcome merge(Path dir, List<Path> files) {
        var text = new StringWriter();
        var args = new java.util.ArrayList<>(List.of("benchmark", "--file", BENCHMARK.toString(), "--runs-dir", dir.resolve("runs").toString(),
            "--expect", EXPECTED.toString(), "--merge"));
        files.forEach(f -> args.add(f.toString()));
        int exit = new CommandLine(new Faultline()).setOut(new PrintWriter(text, true)).execute(args.toArray(String[]::new));
        return new Outcome(exit, text.toString());
    }

    private static List<Path> everyBug(Path dir, String b9) {
        var files = new java.util.ArrayList<Path>();
        for (int i = 1; i <= 8; i++) files.add(job(dir, bug("B" + i, "P" + i, "confirmed", "suspected"), 0));
        files.add(job(dir, bug("B9", null, b9, "suspected"), 0));
        files.add(job(dir, bug("B10", null, "confirmed", "suspected"), 0));
        return files;
    }

    @Test
    void everyBugsJobTogetherReachesTheExpectedScores(@TempDir Path dir) throws Exception {
        var outcome = merge(dir, everyBug(dir, "confirmed"));

        assertThat(outcome.out()).contains("merge     10 file(s), 10 of the 10 bugs")
            .contains("scores    confirmed 10 of 10 planted bugs, 0 false confirmations on the bug-free build; scan flagged 8 of 8")
            .contains("expect    every score reaches what is expected");
        assertThat(outcome.exit()).isEqualTo(Faultline.PASS);
        try (var benchmarks = Files.list(dir.resolve("runs"))) {
            Path merged = benchmarks.findFirst().orElseThrow().resolve("benchmark.json");
            assertThat(Json.MAPPER.readValue(merged.toFile(), Result.class).bugs()).extracting(BugResult::id)
                .containsExactly("B1", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9", "B10");
        }
    }

    @Test
    void aBugThatSlippedOrAJobThatIsMissingFallsShort(@TempDir Path dir) {
        var slipped = merge(dir, everyBug(dir, "suspected"));
        assertThat(slipped.out()).contains("expect    SHORT  hand-written plans confirmed: 9 of 10, expected at least 10");
        assertThat(slipped.exit()).isEqualTo(Faultline.FAIL);

        var missing = merge(dir, everyBug(dir, "confirmed").subList(0, 9));
        assertThat(missing.out()).contains("missing: B10").contains("hand-written plans confirmed: 9 of 10, expected at least 10");
        assertThat(missing.exit()).isEqualTo(Faultline.FAIL);
    }

    @Test
    void aFalseFlagInAnyJobsScanCounts(@TempDir Path dir) {
        var files = everyBug(dir, "confirmed");
        files.set(3, job(dir, bug("B4", "P4", "confirmed", "suspected"), 1));

        var outcome = merge(dir, files);

        assertThat(outcome.out()).contains("scan false flags on the bug-free build: 1, expected at most 0");
        assertThat(outcome.exit()).isEqualTo(Faultline.FAIL);
    }
}
