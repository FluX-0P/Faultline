package dev.faultline.cli.stub;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The fault faultline run armed on the stub: {@code error}, {@code slow} or {@code late-reply} on every nth charge request
 * (retries count too), until it has fired {@code times} times. Remembers each one it fired, for faults.jsonl.
 */
final class ArmedFault {
    static final List<String> ACTIONS = List.of("error", "slow", "late-reply");

    record Fired(String action, String paymentId, Instant at) {}

    private String action;
    private long nthHit;
    private int left;
    private long delayMs;
    private long charges;
    private final List<Fired> fired = new ArrayList<>();

    synchronized void arm(String action, long nthHit, int times, long delayMs) {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("unknown fault '" + action + "': use " + ACTIONS);
        if (nthHit < 1 || times < 1) throw new IllegalArgumentException("nthHit and times must be at least 1");
        this.action = action;
        this.nthHit = nthHit;
        this.left = times;
        this.delayMs = delayMs;
        this.charges = 0;
        this.fired.clear();
    }

    synchronized void disarm() {
        action = null;
        charges = 0;
        fired.clear();
    }

    /** Counts a charge request; returns the fault it gets, or null for none. */
    synchronized String onCharge(String paymentId) {
        charges++;
        if (action == null || charges % nthHit != 0 || left <= 0) return null;
        left--;
        fired.add(new Fired(action, paymentId, Instant.now()));
        return action;
    }

    synchronized long delayMs() {
        return delayMs;
    }

    synchronized List<Fired> fired() {
        return List.copyOf(fired);
    }
}
