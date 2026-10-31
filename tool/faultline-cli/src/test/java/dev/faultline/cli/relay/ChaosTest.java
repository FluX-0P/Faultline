package dev.faultline.cli.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import dev.faultline.cli.relay.Chaos.Message;
import org.junit.jupiter.api.Test;

class ChaosTest {
    private final List<String> fired = new ArrayList<>();

    private static Message m(String key, int n) {
        return new Message(key, "{\"paymentId\":\"pay-" + n + "\",\"accountId\":\"" + key + "\"}");
    }

    private Chaos chaos(String action, long nth, int times) {
        return new Chaos(action, nth, times, "paymentId", (a, id) -> fired.add(a + " " + id));
    }

    @Test
    void withoutAFaultEverythingGoesThroughUntouched() {
        var chaos = chaos(null, 1, 0);
        for (int i = 0; i < 5; i++) assertThat(chaos.accept(m("acct-1", i))).containsExactly(m("acct-1", i));
        assertThat(fired).isEmpty();
    }

    @Test
    void duplicatesEveryNthMessageUntilDone() {
        var chaos = chaos("duplicate", 2, 1);
        assertThat(chaos.accept(m("a", 1))).containsExactly(m("a", 1));
        assertThat(chaos.accept(m("a", 2))).containsExactly(m("a", 2), m("a", 2));
        assertThat(chaos.accept(m("a", 3))).containsExactly(m("a", 3));
        assertThat(chaos.accept(m("a", 4))).containsExactly(m("a", 4));   // times used up
        assertThat(fired).containsExactly("duplicate pay-2");
    }

    @Test
    void disarmedItForwardsAndCountsNothing() {
        var chaos = chaos("duplicate", 2, 1);
        chaos.armed(false);
        for (int i = 0; i < 5; i++) assertThat(chaos.accept(m("a", i))).containsExactly(m("a", i));   // a warm-up
        chaos.armed(true);

        assertThat(chaos.accept(m("a", 10))).containsExactly(m("a", 10));
        assertThat(chaos.accept(m("a", 11))).containsExactly(m("a", 11), m("a", 11));   // the 2nd message since arming
    }

    @Test
    void corruptsIntoInvalidJsonAndRemembersWhichRequest() {
        var chaos = chaos("corrupt", 1, 1);

        var out = chaos.accept(m("a", 7));

        String whole = m("a", 7).value();
        assertThat(out).singleElement().satisfies(msg -> assertThat(msg.value()).isEqualTo(whole.substring(0, whole.length() / 2)));
        assertThat(chaos.corrupted()).containsExactly("pay-7");
    }

    @Test
    void reorderHoldsAMessageUntilTheNextOneWithItsKeyHasGonePast() {
        var chaos = chaos("reorder", 1, 1);
        assertThat(chaos.accept(m("a", 1))).isEmpty();                     // held
        assertThat(chaos.accept(m("b", 2))).containsExactly(m("b", 2));    // other keys pass
        assertThat(chaos.accept(m("a", 3))).containsExactly(m("a", 3), m("a", 1));
        assertThat(chaos.flush()).isEmpty();
    }

    @Test
    void aHeldMessageGoesOutWhenTheTrafficStops() {
        var chaos = chaos("reorder", 1, 1);
        chaos.accept(m("a", 1));
        assertThat(chaos.flush()).containsExactly(m("a", 1));
    }
}
