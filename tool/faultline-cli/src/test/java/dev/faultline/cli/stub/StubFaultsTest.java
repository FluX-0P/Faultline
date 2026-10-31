package dev.faultline.cli.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class StubFaultsTest {
    private final AtomicLong now = new AtomicLong();

    @Test
    void anArmedFaultFiresOnEveryNthChargeUntilDone() {
        var fault = new ArmedFault();
        fault.arm("late-reply", 3, 2, 3_000);

        var got = new ArrayList<String>();
        for (int i = 1; i <= 12; i++) got.add(fault.onCharge("pay-" + i));

        assertThat(got).containsExactly(null, null, "late-reply", null, null, "late-reply", null, null, null, null, null, null);
        assertThat(fault.fired()).extracting(ArmedFault.Fired::paymentId).containsExactly("pay-3", "pay-6");
        assertThat(fault.delayMs()).isEqualTo(3_000);

        fault.disarm();
        assertThat(fault.onCharge("pay-13")).isNull();
        assertThat(fault.fired()).isEmpty();
        assertThatThrownBy(() -> fault.arm("explode", 1, 1, 0)).hasMessageContaining("unknown fault");
    }

    @Test
    void afterAnOutageItStaysDownWhileClientsRetryTooFast() {
        var stub = new Availability(50, now::get);
        assertThat(stub.admit()).isTrue();

        // clients retrying at once: 1,000 requests a second, through the outage and after it
        stub.startOutage();
        for (int i = 0; i < 1_000; i++) {
            now.addAndGet(1_000_000);
            assertThat(stub.admit()).isFalse();
        }
        stub.endOutage();
        for (int i = 0; i < 1_000; i++) {
            now.addAndGet(1_000_000);
            assertThat(stub.admit()).isFalse();
        }
        assertThat(stub.state()).isEqualTo("warming up");

        // the clients back off: a second later the load has dropped, and it is back for good
        now.addAndGet(2_000_000_000L);
        assertThat(stub.admit()).isTrue();
        for (int i = 0; i < 100; i++) {
            now.addAndGet(1_000_000);
            assertThat(stub.admit()).isTrue();
        }
        assertThat(stub.state()).isEqualTo("up");
    }

    @Test
    void clientsThatBackOffGetItBackAsSoonAsTheOutageEnds() {
        var stub = new Availability(50, now::get);
        stub.startOutage();
        for (int i = 0; i < 10; i++) {
            now.addAndGet(500_000_000L);   // two requests a second
            assertThat(stub.admit()).isFalse();
        }
        stub.endOutage();

        now.addAndGet(500_000_000L);
        assertThat(stub.admit()).isTrue();
    }
}
