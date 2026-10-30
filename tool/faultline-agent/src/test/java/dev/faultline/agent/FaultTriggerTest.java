package dev.faultline.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultTriggerTest {
    private final AtomicInteger crashes = new AtomicInteger();

    @Test
    void crashesExactlyOnTheNthHit(@TempDir Path dir) {
        FaultTrigger.armForTest(new FaultPlan("before-ack", "crash", 5, 3, dir.resolve("faults.jsonl")), crashes::incrementAndGet);

        for (int i = 1; i <= 4; i++) FaultTrigger.hit("before-ack");
        assertThat(crashes).hasValue(0);

        FaultTrigger.hit("before-ack");
        assertThat(crashes).hasValue(1);

        for (int i = 0; i < 20; i++) FaultTrigger.hit("before-ack");   // a real crash would have ended the JVM
        assertThat(crashes).hasValue(1);
    }

    @Test
    void recordsTheCrashBeforeFiringIt(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("faults.jsonl");
        FaultTrigger.armForTest(new FaultPlan("before-ack", "crash", 1, 1, log),
            () -> assertThat(log).exists());   // checked at the moment of the crash

        FaultTrigger.hit("before-ack");

        assertThat(Files.readAllLines(log)).singleElement().satisfies(line -> assertThat(line)
            .startsWith("{\"point\":\"before-ack\",\"action\":\"crash\",\"hit\":1,"));
    }

    @Test
    void ignoresOtherPoints(@TempDir Path dir) {
        FaultTrigger.armForTest(new FaultPlan("before-ack", "crash", 1, 1, dir.resolve("faults.jsonl")), crashes::incrementAndGet);

        FaultTrigger.hit("after-save");

        assertThat(crashes).hasValue(0);
    }
}
