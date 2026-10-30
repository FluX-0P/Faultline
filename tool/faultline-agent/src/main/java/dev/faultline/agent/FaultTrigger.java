package dev.faultline.agent;

import java.util.concurrent.atomic.AtomicLong;

/** Counts hits on a fault point and crashes the process on the planned one. */
public final class FaultTrigger {
    /** The exit code of a process killed with kill -9, so the CLI can tell a planned crash from any other exit. */
    public static final int CRASH_EXIT_CODE = 137;

    private static final AtomicLong hits = new AtomicLong();
    private static volatile FaultPlan plan;
    private static volatile Runnable crash = () -> Runtime.getRuntime().halt(CRASH_EXIT_CODE);

    private FaultTrigger() {}

    static void arm(FaultPlan p) {
        hits.set(0);
        plan = p;
    }

    /** Tests swap the crash for something that doesn't end the JVM. */
    static void armForTest(FaultPlan p, Runnable fakeCrash) {
        arm(p);
        crash = fakeCrash;
    }

    /** Called from inside the app's own classes, so it has to stay public. */
    public static void hit(String point) {
        FaultPlan p = plan;
        if (p == null || !p.point().equals(point)) return;
        if (hits.incrementAndGet() != p.nthHit()) return;
        // record first: once halt() runs nothing else happens, and the next start must know this crash is done
        if (FaultLog.record(p.log(), p.point(), p.action(), p.nthHit())) {
            crash.run(); // like kill -9: no shutdown hooks, no cleanup, no offset commit
        }
    }
}
