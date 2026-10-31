package dev.faultline.cli.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import dev.faultline.cli.benchmark.BenchmarkFile;
import dev.faultline.cli.source.App;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The static checks on the demo app the repo ships: they must reach every planted bug that has a pattern. */
class DetectorsTest {
    private static final Path DEMO = Path.of("../../demo-app");
    private static final Path BENCHMARK = Path.of("../../benchmark/bugs.yaml");
    private static List<String> found;

    @BeforeAll
    static void scan() throws Exception {
        var app = App.read(DEMO);
        found = new Detectors(app.code(), app.config()).all().stream().map(c -> c.pattern() + " " + c.method().handler()).toList();
    }

    @Test
    void everyPlantedBugWithAPatternIsACandidateAtItsLocation() throws Exception {
        var withPattern = BenchmarkFile.read(BENCHMARK).bugs().stream().filter(b -> b.pattern() != null).toList();

        assertThat(withPattern).hasSize(8);
        for (var bug : withPattern) assertThat(found).as(bug.id()).contains(bug.pattern() + " " + bug.location());
    }

    @Test
    void codeWithoutThePatternsShapeIsNoCandidate() {
        assertThat(found).doesNotContain(
            "P4 PaymentService#apply",            // an atomic update: balance_minor = balance_minor - ?
            "P4 MerchantBalance#read",             // reads only
            "P4 CompletedEvents#publishOutbox",    // selects, then deletes: not a read-modify-write
            "P5 PaymentController#pay",            // Kafka, not an outside HTTP service
            "P8 PaymentController#pay",            // publishes, but saves nothing
            "P8 CompletedEvents#publish",          // sends what others decided to send
            "P2 PaymentListener#onRetry");         // processes on its own thread
    }

    @Test
    void theDemoAppHasCandidatesForAllEightPatterns() {
        assertThat(found.stream().map(f -> f.substring(0, 2)).distinct().sorted().toList())
            .containsExactly("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8");
    }
}
