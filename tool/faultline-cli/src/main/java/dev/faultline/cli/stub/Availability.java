package dev.faultline.cli.stub;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * Whether the stub takes a request. During an outage it takes none. After one it warms up: it stays down for as long
 * as it gets more than {@code recoverBelow} requests a second, the way a service that has just come back is held down
 * by clients that retry too fast. Once the load drops below that, it is back for good.
 */
final class Availability {
    private static final long SECOND = 1_000_000_000L;

    private final int recoverBelow;
    private final LongSupplier nanoTime;
    private final ArrayDeque<Long> lastSecond = new ArrayDeque<>();
    private boolean down;
    private boolean warmingUp;

    Availability(int recoverBelow, LongSupplier nanoTime) {
        this.recoverBelow = recoverBelow;
        this.nanoTime = nanoTime;
    }

    /** Counts the request, and says whether to serve it. */
    synchronized boolean admit() {
        long now = nanoTime.getAsLong();
        lastSecond.addLast(now);
        while (now - lastSecond.peekFirst() >= SECOND) lastSecond.removeFirst();
        if (down) return false;
        if (warmingUp) {
            if (lastSecond.size() > recoverBelow) return false;
            warmingUp = false;
        }
        return true;
    }

    synchronized void startOutage() {
        down = true;
    }

    synchronized void endOutage() {
        down = false;
        warmingUp = true;
    }

    synchronized void reset() {
        down = false;
        warmingUp = false;
    }

    synchronized String state() {
        return down ? "down" : warmingUp ? "warming up" : "up";
    }
}
