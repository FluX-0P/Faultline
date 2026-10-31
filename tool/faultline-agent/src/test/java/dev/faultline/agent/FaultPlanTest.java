package dev.faultline.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultPlanTest {

    @Test
    void readsThePlanTheCliWrites(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("faults.jsonl");   // on Windows this has backslashes, which properties must escape
        Path file = writePlan(dir, Map.of("point", "PaymentService#apply", "when", "after", "action", "delay",
            "delay_ms", "15000", "nth_hit", "300", "times", "1", "log", log.toString()));

        assertThat(FaultPlan.load(file)).isEqualTo(new FaultPlan("PaymentService#apply", "after", "delay", 300, 1, 15_000, log));
        assertThat(FaultPlan.load(file).typeName()).isEqualTo("PaymentService");
        assertThat(FaultPlan.load(file).methodName()).isEqualTo("apply");
    }

    @Test
    void aPhase3PlanStillReads(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("faults.jsonl");
        Path file = writePlan(dir, Map.of("point", "before-ack", "nth_hit", "300", "times", "3", "log", log.toString()));

        assertThat(FaultPlan.load(file)).isEqualTo(new FaultPlan("before-ack", "before", "crash", 300, 3, 0, log));
    }

    @Test
    void remainingCountsFaultsFromEarlierLives(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("faults.jsonl");
        var plan = new FaultPlan("before-ack", "before", "crash", 300, 3, 0, log);
        assertThat(plan.remaining()).isEqualTo(3);

        FaultLog.record(log, "before-ack", "crash", 300);
        FaultLog.record(log, "before-ack", "crash", 300);

        assertThat(plan.remaining()).isEqualTo(1);
    }

    @Test
    void rejectsAMissingOrBadSetting(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> FaultPlan.load(writePlan(dir, Map.of("point", "before-ack", "times", "3", "log", "f"))))
            .hasMessageContaining("nth_hit");
        assertThatThrownBy(() -> new FaultPlan("before-ack", "before", "freeze", 1, 1, 0, dir)).hasMessageContaining("unsupported action");
        assertThatThrownBy(() -> new FaultPlan("PaymentService", "before", "crash", 1, 1, 0, dir)).hasMessageContaining("Class#method");
        assertThatThrownBy(() -> new FaultPlan("A#b", "before", "delay", 1, 1, 0, dir)).hasMessageContaining("delay_ms");
    }

    private static Path writePlan(Path dir, Map<String, String> settings) throws Exception {
        var p = new Properties();
        p.putAll(settings);
        Path file = dir.resolve("agent-plan.properties");
        try (Writer out = Files.newBufferedWriter(file)) {
            p.store(out, null);
        }
        return file;
    }
}
