package dev.faultline.agent;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Counts hits on the fault point and fires the planned fault on every nth one, until none are left. */
public final class FaultTrigger {
    /** The exit code of a process killed with kill -9, so the CLI can tell a planned crash from any other exit. */
    public static final int CRASH_EXIT_CODE = 137;

    private static final long ARM_CHECK_NANOS = 100_000_000L;
    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicInteger left = new AtomicInteger();
    private static volatile FaultPlan plan;
    private static volatile boolean armed;
    private static volatile long lastArmCheck;
    private static volatile Runnable crash = () -> Runtime.getRuntime().halt(CRASH_EXIT_CODE);

    private FaultTrigger() {}

    static void arm(FaultPlan p, int remaining) {
        hits.set(0);
        left.set(remaining);
        armed = false;
        lastArmCheck = System.nanoTime() - ARM_CHECK_NANOS;
        plan = p;
    }

    /** Tests swap the crash for something that doesn't end the JVM. */
    static void armForTest(FaultPlan p, int remaining, Runnable fakeCrash) {
        arm(p, remaining);
        crash = fakeCrash;
    }

    /** Called from the advice copied into the app's own methods, so it has to stay public. */
    public static void hit() {
        FaultPlan p = plan;
        if (p == null || !armed(p)) return;
        long n = hits.incrementAndGet();
        if (n % p.nthHit() != 0 || left.getAndDecrement() <= 0) return;
        // record first: after a crash nothing else happens, and the next start must know this fault is done
        if (!FaultLog.record(p.log(), p.point(), p.action(), n)) return;
        switch (p.action()) {
            case "crash" -> crash.run();   // like kill -9: no shutdown hooks, no cleanup, no offset commit
            case "delay" -> pause(p.delayMs());
            case "throw" -> throw new IllegalStateException("faultline: injected failure at " + p.point());
            case "barrier" -> hold(p);
            default -> { }
        }
    }

    /**
     * Whether calls count yet. With an arm file they count only once it exists, so a warm-up before the traffic does
     * not use up the plan's hits. Looks for the file at most every 100ms.
     */
    private static boolean armed(FaultPlan p) {
        if (armed || p.armFile() == null) return true;
        long now = System.nanoTime();
        if (now - lastArmCheck < ARM_CHECK_NANOS) return false;
        lastArmCheck = now;
        if (!Files.exists(p.armFile())) return false;
        armed = true;
        FaultAgent.log("armed: counting calls to " + p.point() + " from now on");
        return true;
    }

    /** Holds this thread between two steps of the app until another worker has passed the until point. */
    private static void hold(FaultPlan p) {
        long start = System.nanoTime();
        boolean released = Barrier.hold(p.delayMs());
        long waited = (System.nanoTime() - start) / 1_000_000;
        FaultAgent.log(released
            ? "barrier at %s let go after %dms: another worker passed %s".formatted(p.point(), waited, p.until())
            : "barrier at %s gave up after %dms: nobody passed %s".formatted(p.point(), waited, p.until()));
    }

    /** Holds the calling thread, like a long GC pause would. Other threads, such as Kafka's heartbeat, keep going. */
    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
