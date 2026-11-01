package dev.faultline.cli.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.faultline.cli.relay.Chaos.Message;
import org.junit.jupiter.api.Test;

class ChaosTest {
    private final List<String> fired = new ArrayList<>();

    private static Message m(String key, int n) {
        return new Message(key, "{\"paymentId\":\"pay-" + n + "\",\"accountId\":\"" + key + "\"}");
    }

    /** key:value of each message, since a record of byte arrays compares them by reference. */
    private static List<String> text(List<Message> messages) {
        return messages.stream().map(m -> new String(m.key(), StandardCharsets.UTF_8) + ":" + m.text()).toList();
    }

    private static List<String> text(Message... messages) {
        return text(List.of(messages));
    }

    private Chaos chaos(String action, long nth, int times) {
        return new Chaos(action, nth, times, "paymentId", (a, id) -> fired.add(a + " " + id));
    }

    @Test
    void withoutAFaultEverythingGoesThroughUntouched() {
        var chaos = chaos(null, 1, 0);
        for (int i = 0; i < 5; i++) assertThat(text(chaos.accept(m("acct-1", i)))).isEqualTo(text(m("acct-1", i)));
        assertThat(fired).isEmpty();
    }

    @Test
    void duplicatesEveryNthMessageUntilDone() {
        var chaos = chaos("duplicate", 2, 1);
        assertThat(text(chaos.accept(m("a", 1)))).isEqualTo(text(m("a", 1)));
        assertThat(text(chaos.accept(m("a", 2)))).isEqualTo(text(m("a", 2), m("a", 2)));
        assertThat(text(chaos.accept(m("a", 3)))).isEqualTo(text(m("a", 3)));
        assertThat(text(chaos.accept(m("a", 4)))).isEqualTo(text(m("a", 4)));   // times used up
        assertThat(fired).containsExactly("duplicate pay-2");
    }

    @Test
    void disarmedItForwardsAndCountsNothing() {
        var chaos = chaos("duplicate", 2, 1);
        chaos.armed(false);
        for (int i = 0; i < 5; i++) assertThat(text(chaos.accept(m("a", i)))).isEqualTo(text(m("a", i)));   // a warm-up
        chaos.armed(true);

        assertThat(text(chaos.accept(m("a", 10)))).isEqualTo(text(m("a", 10)));
        assertThat(text(chaos.accept(m("a", 11)))).isEqualTo(text(m("a", 11), m("a", 11)));   // the 2nd message since arming
    }

    @Test
    void corruptsIntoInvalidJsonAndRemembersWhichRequest() {
        var chaos = chaos("corrupt", 1, 1);

        var out = chaos.accept(m("a", 7));

        String whole = m("a", 7).text();
        assertThat(out).singleElement().satisfies(msg -> assertThat(msg.text()).isEqualTo(whole.substring(0, whole.length() / 2)));
        assertThat(chaos.corrupted()).containsExactly("pay-7");
        assertThat(chaos.unreadable()).isEmpty();
    }

    @Test
    void aBinaryMessageIsCutInHalfAndKeptWholeForFindingItsRequestLater() {
        var chaos = chaos("corrupt", 1, 1);
        // Confluent Avro: magic byte, schema ID, then the record, a string as its zigzag length and its bytes
        byte[] avro = {0, 0, 0, 0, 7, 18, 'p', 'a', 'y', '-', '4', '2', '-', '1', '2', 2, 'x'};
        var header = new Chaos.Header("trace", new byte[] {1, 2});

        var out = chaos.accept(new Message(new byte[] {9}, avro, List.of(header), "3@41"));

        assertThat(out).singleElement().satisfies(msg -> {
            assertThat(msg.value()).isEqualTo(Arrays.copyOf(avro, avro.length / 2));
            assertThat(msg.headers()).containsExactly(header);
        });
        assertThat(chaos.corrupted()).isEmpty();
        assertThat(chaos.unreadable()).singleElement().satisfies(msg -> assertThat(msg.value()).isEqualTo(avro));
        assertThat(fired).containsExactly("corrupt the message at 3@41");
    }

    @Test
    void reorderHoldsAMessageUntilTheNextOneWithItsKeyHasGonePast() {
        var chaos = chaos("reorder", 1, 1);
        assertThat(chaos.accept(m("a", 1))).isEmpty();                                    // held
        assertThat(text(chaos.accept(m("b", 2)))).isEqualTo(text(m("b", 2)));             // other keys pass
        assertThat(text(chaos.accept(m("a", 3)))).isEqualTo(text(m("a", 3), m("a", 1)));
        assertThat(chaos.flush()).isEmpty();
    }

    @Test
    void aHeldMessageGoesOutWhenTheTrafficStops() {
        var chaos = chaos("reorder", 1, 1);
        chaos.accept(m("a", 1));
        assertThat(text(chaos.flush())).isEqualTo(text(m("a", 1)));
    }
}
