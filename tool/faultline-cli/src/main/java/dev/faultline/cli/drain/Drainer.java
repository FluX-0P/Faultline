package dev.faultline.cli.drain;

import java.time.Duration;

/**
 * Waits for the app to finish processing: done once the result count has stopped changing for a quiet period.
 * Simple and app-agnostic; a Kafka consumer-lag check replaces it later.
 */
public final class Drainer {
    public static final Duration QUIET = Duration.ofSeconds(10);
    public static final Duration MAX_WAIT = Duration.ofMinutes(2);
    static final Duration POLL = Duration.ofSeconds(1);

    @FunctionalInterface
    public interface Counter {
        long count() throws Exception;
    }

    /** {@code settled} is false when MAX_WAIT ran out while the count was still changing. */
    public record Result(long count, boolean settled, Duration waited) {}

    private Drainer() {}

    public static Result waitUntilStable(Counter counter) throws Exception {
        return waitUntilStable(counter, QUIET, MAX_WAIT, POLL);
    }

    static Result waitUntilStable(Counter counter, Duration quiet, Duration maxWait, Duration poll) throws Exception {
        long start = System.nanoTime();
        long last = counter.count();
        long lastChange = start;
        while (true) {
            long now = System.nanoTime();
            if (now - lastChange >= quiet.toNanos()) {
                return new Result(last, true, Duration.ofNanos(now - start));
            }
            if (now - start >= maxWait.toNanos()) {
                return new Result(last, false, Duration.ofNanos(now - start));
            }
            Thread.sleep(poll);
            long current = counter.count();
            if (current != last) {
                last = current;
                lastChange = System.nanoTime();
            }
        }
    }
}
