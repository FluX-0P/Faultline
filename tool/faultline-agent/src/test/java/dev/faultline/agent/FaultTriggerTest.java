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
    void aBarrierHoldsUntilAnotherThreadPassesTheUntilPoint(@TempDir Path dir) throws Exception {
        var plan = new FaultPlan("MerchantBalance#read", "after", "barrier", 1, 1, 5_000, dir.resolve("faults.jsonl"), "MerchantBalance#write", 0, null);
        FaultTrigger.armForTest(plan, 1, crashes::incrementAndGet);
        var partner = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            Barrier.passed();
        });
        partner.start();

        long start = System.nanoTime();
        FaultTrigger.hit();
        long waited = (System.nanoTime() - start) / 1_000_000;

        partner.join();
        Barrier.resetForTest();
        assertThat(waited).isBetween(150L, 2_000L);
        assertThat(Files.readAllLines(dir.resolve("faults.jsonl"))).singleElement().satisfies(line -> assertThat(line).contains("\"action\":\"barrier\""));
    }

    @Test
    void withAnArmFileCallsCountOnlyOnceItExists(@TempDir Path dir) throws Exception {
        Path armFile = dir.resolve("armed");
        var plan = new FaultPlan("PaymentService#apply", "before", "crash", 2, 1, 0, dir.resolve("faults.jsonl"), null, 0, null, armFile);
        FaultTrigger.armForTest(plan, 1, crashes::incrementAndGet);

        for (int i = 0; i < 10; i++) FaultTrigger.hit();   // a warm-up: not counted
        assertThat(crashes).hasValue(0);

        Files.createFile(armFile);
        Thread.sleep(150);   // the trigger looks for the file at most every 100ms
        FaultTrigger.hit();
        assertThat(crashes).hasValue(0);
        FaultTrigger.hit();   // the 2nd call since it was armed
        assertThat(crashes).hasValue(1);
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
