package dev.faultline.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultTriggerTest {
    private final AtomicInteger crashes = new AtomicInteger();

    private static FaultPlan plan(String action, long nthHit, int times, long delayMs, Path dir) {
        return new FaultPlan("PaymentService#apply", "before", action, nthHit, times, delayMs, dir.resolve("faults.jsonl"));
    }

    @Test
    void firesOnEveryNthHitUntilNoneAreLeft(@TempDir Path dir) {
        FaultTrigger.armForTest(plan("crash", 5, 2, 0, dir), 2, crashes::incrementAndGet);

        for (int i = 1; i <= 4; i++) FaultTrigger.hit();
        assertThat(crashes).hasValue(0);

        FaultTrigger.hit();                                   // hit 5
        assertThat(crashes).hasValue(1);
        for (int i = 6; i <= 10; i++) FaultTrigger.hit();     // hit 10: a fake crash lets the JVM live on
        assertThat(crashes).hasValue(2);
        for (int i = 11; i <= 40; i++) FaultTrigger.hit();    // none left
        assertThat(crashes).hasValue(2);
    }

    @Test
    void recordsTheFaultBeforeFiringIt(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("faults.jsonl");
        FaultTrigger.armForTest(plan("crash", 1, 1, 0, dir), 1, () -> assertThat(log).exists());   // checked at the moment of the crash

        FaultTrigger.hit();

        assertThat(Files.readAllLines(log)).singleElement().satisfies(line -> assertThat(line)
            .startsWith("{\"point\":\"PaymentService#apply\",\"action\":\"crash\",\"hit\":1,"));
    }

    @Test
    void delayHoldsTheCallingThread(@TempDir Path dir) {
        FaultTrigger.armForTest(plan("delay", 1, 1, 200, dir), 1, crashes::incrementAndGet);

        long start = System.nanoTime();
        FaultTrigger.hit();

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(200);
        assertThat(crashes).hasValue(0);
    }

    @Test
    void throwFailsTheCallLikeABugWould(@TempDir Path dir) {
        FaultTrigger.armForTest(plan("throw", 2, 1, 0, dir), 1, crashes::incrementAndGet);

        FaultTrigger.hit();
        assertThatThrownBy(FaultTrigger::hit).isInstanceOf(IllegalStateException.class).hasMessageContaining("PaymentService#apply");
        FaultTrigger.hit();
        FaultTrigger.hit();   // none left: no second throw
    }
}
