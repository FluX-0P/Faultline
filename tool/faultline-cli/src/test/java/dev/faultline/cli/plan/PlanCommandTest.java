package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.TestApps;
import dev.faultline.cli.understand.AppMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanCommandTest {
    private static final Risk RISK = new Risk("R1", "P1", "Save, then acknowledge the message", "PaymentListener#onMessage",
        "src/main/java/pay/PaymentListener.java", 8, "high", "no duplicate check");
    private static final AppMap MAP = new AppMap("payments", "m", "Payments.", List.of(), List.of(), List.of(), List.of(),
        List.of(new AppMap.Journey("make-payment", "pay", List.of(new AppMap.Step("PaymentController#pay", "accept")))));

    @Test
    void readsTheFactsFromTheAppNotTheLlm(@TempDir Path dir) throws Exception {
        var facts = PlanCommand.facts(App.read(TestApps.payments(dir)), RISK);

        assertThat(facts.url()).isEqualTo("http://localhost:8081");
        assertThat(facts.dbUrl()).isEqualTo("jdbc:postgresql://localhost:5432/pay");
        assertThat(facts.topic()).isEqualTo("payments.in");
        assertThat(facts.group()).isEqualTo("from-config");   // the listener names no group, so the config's
        assertThat(facts.jar()).endsWith("target/payments-1.2.0.jar");   // not built yet: the name Maven will give it
    }

    @Test
    void aGoodDraftMakesAValidPlan(@TempDir Path dir) throws Exception {
        var app = App.read(TestApps.payments(dir));
        var draft = draft("make-payment", "/api/payments", List.of("TRUNCATE payments;"));

        assertThat(PlanCommand.problems(draft, app, MAP, RISK, PlanCommand.facts(app, RISK))).isEmpty();
        Plan plan = PlanCommand.build(draft, app.name(), RISK, PlanCommand.facts(app, RISK));
        assertThat(plan.database().reset()).containsExactly("TRUNCATE payments");
        assertThat(plan.fault()).isEqualTo(new Plan.Fault(null, "before-ack", null, "crash", 300L, 3, null, null, null));
        assertThat(plan.confirm()).isEqualTo(new Plan.Confirm(2, 1, 2));
    }

    @Test
    void catchesAnInventedEndpointAndAResetThatLeavesRowsBehind(@TempDir Path dir) throws Exception {
        var app = App.read(TestApps.payments(dir));
        var draft = draft("checkout", "/payments", List.of("UPDATE accounts SET balance = 0"));

        assertThat(PlanCommand.problems(draft, app, MAP, RISK, PlanCommand.facts(app, RISK))).contains(
            "journey 'checkout' is not in the app map (journeys: make-payment)",
            "POST /payments is not an endpoint in the code (endpoints: POST /api/payments)",
            "reset does not empty payments, which the risky code inserts into: rows left by one run would break the next");
    }

    private static PlanDraft draft(String journey, String path, List<String> reset) {
        return new PlanDraft(journey, new PlanDraft.Request("POST", path, "{\"paymentId\":\"p-${seed}-${seq}\",\"amount\":${int:1:9}}"),
            "paymentId", new PlanDraft.Effect("payments", "payment_id"), reset, "reaches the listener");
    }
}
