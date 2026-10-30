package dev.faultline.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultPlanTest {

    @Test
    void readsThePlanTheCliWrites(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("faults.jsonl");   // on Windows this has backslashes, which properties must escape
        Path file = writePlan(dir, "before-ack", "300", "3", log);

        var plan = FaultPlan.load(file);

        assertThat(plan).isEqualTo(new FaultPlan("before-ack", "crash", 300, 3, log));
    }

    @Test
    void remainingCountsCrashesFromEarlierLives(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("faults.jsonl");
        var plan = new FaultPlan("before-ack", "crash", 300, 3, log);
        assertThat(plan.remaining()).isEqualTo(3);

        FaultLog.record(log, "before-ack", "crash", 300);
        FaultLog.record(log, "before-ack", "crash", 300);

        assertThat(plan.remaining()).isEqualTo(1);
    }

    @Test
    void rejectsAMissingOrBadSetting(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> FaultPlan.load(writePlan(dir, "before-ack", "", "3", dir.resolve("f"))))
            .hasMessageContaining("nth_hit");
        assertThatThrownBy(() -> new FaultPlan("before-ack", "freeze", 1, 1, dir))
            .hasMessageContaining("unsupported action");
    }

    private static Path writePlan(Path dir, String point, String nthHit, String times, Path log) throws Exception {
        var p = new Properties();
        p.setProperty("point", point);
        p.setProperty("nth_hit", nthHit);
        p.setProperty("times", times);
        p.setProperty("log", log.toString());
        Path file = dir.resolve("agent-plan.properties");
        try (Writer out = Files.newBufferedWriter(file)) {
            p.store(out, null);
        }
        return file;
    }
}
