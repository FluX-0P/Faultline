package dev.faultline.cli.workload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentWorkloadTest {
    private final PaymentWorkload workload = new PaymentWorkload();

    @Test
    void sameSeedGivesSamePayments() {
        assertThat(workload.generate(42, 500)).isEqualTo(workload.generate(42, 500));
    }

    @Test
    void differentSeedGivesDifferentPayments() {
        assertThat(workload.generate(42, 500)).isNotEqualTo(workload.generate(43, 500));
    }

    @Test
    void seed42StaysTheSameForever() {
        // pinned values: if these change, old reproduce commands no longer reproduce
        assertThat(workload.generate(42, 3)).containsExactly(
            new Payment(0, "pay-42-0", "acct-31", 4111),
            new Payment(1, "pay-42-1", "acct-49", 3479),
            new Payment(2, "pay-42-2", "acct-71", 3835));
    }

    @Test
    void paymentsStayWithinTheDemoAppsData() {
        var payments = workload.generate(7, 5_000);
        assertThat(payments).extracting(Payment::paymentId).doesNotHaveDuplicates();
        assertThat(payments).allSatisfy(p -> {
            assertThat(p.paymentId()).isEqualTo("pay-7-" + p.index());
            int account = Integer.parseInt(p.accountId().substring("acct-".length()));
            assertThat(account).isBetween(1, PaymentWorkload.ACCOUNTS);
            assertThat(p.amountMinor()).isBetween((long) PaymentWorkload.MIN_AMOUNT, (long) PaymentWorkload.MAX_AMOUNT);
        });
    }

    @Test
    void requestBodyMatchesTheDemoAppsJson() {
        assertThat(workload.requestBody(new Payment(0, "pay-1", "acct-1", 2500)))
            .isEqualTo("{\"paymentId\":\"pay-1\",\"accountId\":\"acct-1\",\"amountMinor\":2500}");
    }
}
