package dev.faultline.cli.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultSpecTest {

    @Test
    void writesThePlanTheAgentReads(@TempDir Path runDir) throws Exception {
        var env = new FaultSpec("before-ack", 300, 3).writeAgentPlan(runDir);

        Path planFile = Path.of(env.get("FAULTLINE_PLAN"));
        var plan = new Properties();
        try (Reader in = Files.newBufferedReader(planFile)) {
            plan.load(in);
        }
        assertThat(planFile).isAbsolute().hasFileName("agent-plan.properties");
        assertThat(plan).containsEntry("point", "before-ack").containsEntry("action", "crash")
            .containsEntry("nth_hit", "300").containsEntry("times", "3")
            .containsEntry("log", runDir.resolve("faults.jsonl").toAbsolutePath().toString());
    }

    @Test
    void countsFiredFaultsFromTheAgentsLog(@TempDir Path runDir) throws Exception {
        assertThat(FaultSpec.fired(runDir)).isZero();

        Files.writeString(runDir.resolve("faults.jsonl"), "{\"point\":\"before-ack\"}\n{\"point\":\"before-ack\"}\n");

        assertThat(FaultSpec.fired(runDir)).isEqualTo(2);
    }

    @Test
    void rejectsUnknownPointsAndBadCounts() {
        assertThatThrownBy(() -> new FaultSpec("after-lunch", 1, 1)).hasMessageContaining("unknown fault point");
        assertThatThrownBy(() -> new FaultSpec("before-ack", 0, 1)).hasMessageContaining("--nth-hit");
        assertThatThrownBy(() -> new FaultSpec("before-ack", 1, 0)).hasMessageContaining("--times");
    }
}
