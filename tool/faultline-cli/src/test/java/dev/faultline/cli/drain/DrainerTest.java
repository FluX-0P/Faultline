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
    void settlesOnceTheCountStopsChangingAndNothingIsLeft() throws Exception {
        var polls = new AtomicLong();
        // grows for the first 10 polls, then stays at 10; nothing left to process throughout
        var result = Drainer.waitUntilDone(() -> Math.min(polls.incrementAndGet(), 10), () -> 0, QUIET, MAX, POLL);

        assertThat(result.settled()).isTrue();
        assertThat(result.count()).isEqualTo(10);
        assertThat(result.waited()).isGreaterThanOrEqualTo(QUIET);
    }

    @Test
    void keepsWaitingWhileMessagesAreLeftEvenIfTheCountIsStill() throws Exception {
        // like a restart: nothing new lands for a while, but one message is still unacknowledged
        var polls = new AtomicLong();
        var result = Drainer.waitUntilDone(() -> 5, () -> polls.incrementAndGet() < 30 ? 1 : 0, QUIET, MAX, POLL);

        assertThat(result.settled()).isTrue();
        assertThat(polls.get()).isGreaterThanOrEqualTo(30);
    }

    @Test
    void givesUpWhenItNeverSettles() throws Exception {
        var result = Drainer.waitUntilDone(() -> 5, () -> 1, QUIET, Duration.ofMillis(500), POLL);

        assertThat(result.settled()).isFalse();
        assertThat(result.backlog()).isEqualTo(1);
        assertThat(result.waited()).isGreaterThanOrEqualTo(Duration.ofMillis(500));
    }
}
