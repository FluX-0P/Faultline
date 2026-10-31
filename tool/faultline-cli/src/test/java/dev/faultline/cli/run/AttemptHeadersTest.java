package dev.faultline.cli.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.plan.TestPlans;
import org.junit.jupiter.api.Test;

class AttemptHeadersTest {

    private static Plan withAuth(String value) {
        var p = TestPlans.valid();
        var t = p.target();
        return new Plan(p.app(), p.journey(), p.risk(), new Plan.Target(t.url(), t.jar(), null, t.args(), new Plan.Auth("Authorization", value)),
            p.database(), p.kafka(), null, null, null, p.traffic(), p.correlateBy(), null, null, p.fault(), p.checks(), null, null, p.confirm());
    }

    @Test
    void aPlanWithoutAuthSendsNoHeaders() {
        assertThat(Attempt.headers(TestPlans.valid())).isEmpty();
        assertThat(Attempt.headers(withAuth("Basic ZGVtbzpkZW1v"))).containsEntry("Authorization", "Basic ZGVtbzpkZW1v");
    }

    @Test
    void aSecretThatIsNotSetStopsTheRunBeforeItStarts() {
        assertThatThrownBy(() -> Attempt.headers(withAuth("Bearer ${env:FAULTLINE_TEST_TOKEN_NEVER_SET}")))
            .hasMessageContaining("FAULTLINE_TEST_TOKEN_NEVER_SET is not set: target.auth.value needs it");
    }
}
