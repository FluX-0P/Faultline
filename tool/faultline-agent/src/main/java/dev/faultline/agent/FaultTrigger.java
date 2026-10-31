package dev.faultline.agent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Counts hits on the fault point and fires the planned fault on every nth one, until none are left. */
public final class FaultTrigger {
    /** The exit code of a process killed with kill -9, so the CLI can tell a planned crash from any other exit. */
    public static final int CRASH_EXIT_CODE = 137;

    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicInteger left = new AtomicInteger();
    private static volatile FaultPlan plan;
    private static volatile Runnable crash = () -> Runtime.getRuntime().halt(CRASH_EXIT_CODE);

    private FaultTrigger() {}

    static void arm(FaultPlan p, int remaining) {
        hits.set(0);
        left.set(remaining);
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
        if (p == null) return;
        long n = hits.incrementAndGet();
        if (n % p.nthHit() != 0 || left.getAndDecrement() <= 0) return;
        // record first: after a crash nothing else happens, and the next start must know this fault is done
        if (!FaultLog.record(p.log(), p.point(), p.action(), n)) return;
        switch (p.action()) {
            case "crash" -> crash.run();   // like kill -9: no shutdown hooks, no cleanup, no offset commit
            case "delay" -> pause(p.delayMs());
            case "throw" -> throw new IllegalStateException("faultline: injected failure at " + p.point());
            default -> { }
        }
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
