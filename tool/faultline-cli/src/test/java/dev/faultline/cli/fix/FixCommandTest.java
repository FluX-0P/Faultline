package dev.faultline.cli.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import dev.faultline.cli.plan.TestPlans;
import dev.faultline.cli.report.Finding;
import dev.faultline.cli.source.App;
import org.junit.jupiter.api.Test;

class FixCommandTest {
    private static final Path DEMO = Path.of("../../demo-app");

    @Test
    void theLlmSeesThePatternTheFaultTheEvidenceAndTheCodeAroundTheBug() throws Exception {
        var app = App.read(DEMO);
        var finding = new Finding(Finding.Label.CONFIRMED, "no-duplicates failed in 2 of 2 runs with the fault", "R1", "P1",
            "Save, then acknowledge the message", "PaymentListener#onMessage", "src/main/java/dev/faultline/demo/payment/PaymentListener.java", 36,
            "make-payment", "high", "the ack comes after the commit", List.of("pay-42-299 (2x)"));

        String prompt = FixCommand.prompt(app, TestPlans.valid(), finding, app.code().method("PaymentListener#onMessage").orElseThrow());

        assertThat(prompt).contains("The bug: P1 Save, then acknowledge the message at PaymentListener#onMessage")
            .contains("Kafka delivers the")                       // the pattern's risk
            .contains("The fault Faultline fired: code crash just before the listener acknowledges")
            .contains("- pay-42-299 (2x)")
            .contains("class PaymentListener").contains("class PaymentService");
    }

    @Test
    void aSuggestionWithoutTheChangeIsSentBack() {
        assertThat(new FixCommand.Suggestion("w", "s", "f", " ", "").problems()).containsExactly("change is empty: show the code as it should be");
    }
}
