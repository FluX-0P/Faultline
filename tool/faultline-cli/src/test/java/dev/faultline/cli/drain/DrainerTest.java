package dev.faultline.cli.drain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class DrainerTest {
    private static final Duration QUIET = Duration.ofMillis(200);
    private static final Duration MAX = Duration.ofSeconds(2);
    private static final Duration POLL = Duration.ofMillis(20);

    @Test
    void settlesOnceTheCountStopsChanging() throws Exception {
        var calls = new AtomicLong();
        // grows for the first 10 polls, then stays at 10
        var result = Drainer.waitUntilStable(() -> Math.min(calls.incrementAndGet(), 10), QUIET, MAX, POLL);

        assertThat(result.settled()).isTrue();
        assertThat(result.count()).isEqualTo(10);
        assertThat(result.waited()).isGreaterThanOrEqualTo(QUIET);
    }

    @Test
    void givesUpWhenTheCountNeverSettles() throws Exception {
        var calls = new AtomicLong();
        var result = Drainer.waitUntilStable(calls::incrementAndGet, QUIET, Duration.ofMillis(500), POLL);

        assertThat(result.settled()).isFalse();
        assertThat(result.waited()).isGreaterThanOrEqualTo(Duration.ofMillis(500));
    }
}
