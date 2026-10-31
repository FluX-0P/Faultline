package dev.faultline.cli.drain;

import java.time.Duration;

/**
 * Waits for the app to finish processing: done once nothing is left to process (Kafka lag is zero) and the
 * result count has stopped changing for a quiet period. Lag alone survives a restart that takes a while;
 * the quiet period catches results that land just after the last acknowledgment.
 */
public final class Drainer {
    public static final Duration QUIET = Duration.ofSeconds(3);
    public static final Duration MAX_WAIT = Duration.ofMinutes(3);
    public static final Duration POLL = Duration.ofSeconds(1);

    @FunctionalInterface
    public interface Counter {
        long count() throws Exception;
    }

    /** {@code settled} is false when MAX_WAIT ran out first; {@code backlog} is what was still left then. */
    public record Result(long count, long backlog, boolean settled, Duration waited) {}

    private Drainer() {}

    public static Result waitUntilDone(Counter results, Counter backlog) throws Exception {
        return waitUntilDone(results, backlog, QUIET, MAX_WAIT, POLL);
    }

    public static Result waitUntilDone(Counter results, Counter backlog, Duration quiet, Duration maxWait, Duration poll) throws Exception {
        long start = System.nanoTime();
        long last = results.count();
        long left = backlog.count();
        long quietSince = start;
        while (true) {
            long now = System.nanoTime();
            if (left == 0 && now - quietSince >= quiet.toNanos()) {
                return new Result(last, 0, true, Duration.ofNanos(now - start));
            }
            if (now - start >= maxWait.toNanos()) {
                return new Result(last, left, false, Duration.ofNanos(now - start));
            }
            Thread.sleep(poll);
            long current = results.count();
            left = backlog.count();
            if (current != last || left > 0) {
                last = current;
                quietSince = System.nanoTime();
            }
        }
    }
}
