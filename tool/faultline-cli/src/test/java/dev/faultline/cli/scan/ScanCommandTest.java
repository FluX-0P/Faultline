package dev.faultline.cli.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.scan.PatternReview.Verdict;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import org.junit.jupiter.api.Test;

class ScanCommandTest {

    private static Risk risk(String pattern, String confidence, String location) {
        return new Risk(null, pattern, "t", location, "f", 1, confidence, "w");
    }

    @Test
    void ranksMoneyOverLostDataOverSlowRecoveryThenByConfidence() {
        var risks = new ArrayList<>(List.of(risk("P6", "high", "A#a"), risk("P2", "high", "B#b"), risk("P5", "low", "C#c"),
            risk("P4", "high", "D#d"), risk("P8", "medium", "E#e")));

        risks.sort(ScanCommand.ranking());

        assertThat(risks).extracting(Risk::pattern).containsExactly("P4", "P5", "P2", "P8", "P6");
    }

    @Test
    void aReviewMustAnswerEveryCandidateOnce() {
        var ids = List.of("C1", "C2");
        assertThat(new PatternReview(List.of(new Verdict("C1", true, "high", "x"), new Verdict("C2", false, "low", "y"))).problems(ids)).isEmpty();

        assertThat(new PatternReview(List.of(new Verdict("C1", true, "sure", ""), new Verdict("C1", null, "high", "x"), new Verdict("C9", true, "high", "x")))
            .problems(ids)).contains(
                "C1: confidence must be low, medium or high", "C1: why is empty", "candidate C1 is answered twice",
                "C1: risky must be true or false", "there is no candidate C9; use [C1, C2]", "candidate C2 has no verdict");
    }

    @Test
    void thePromptNamesEachCandidateAndShowsTheCodeThatReachesItWithoutComments() throws Exception {
        var app = App.read(Path.of("../../demo-app"));
        var p4 = Detectors.byPattern(new Detectors(app.code(), app.config()).all()).get("P4");

        String prompt = ScanCommand.prompt(app, RiskPattern.byId("P4").orElseThrow(), p4, null);

        assertThat(prompt).contains("- C1: MerchantBalance#credit (src/main/java/dev/faultline/demo/payment/MerchantBalance.java, line 25)")
            .contains("class MerchantBalance").contains("class PaymentService").contains("class PaymentListener")
            .contains("payments.merchant-balance=read-then-write").contains("create table merchant")
            .doesNotContain("B4").doesNotContain("App map");
        assertThat(ScanCommand.system(RiskPattern.byId("P4").orElseThrow())).contains("The pattern: P4 Read, modify, save without locking.");
    }

    @Test
    void aConsumerSidePatternAlsoSeesWhereTheMessagesAndTheirIdsComeFrom() throws Exception {
        var app = App.read(Path.of("../../demo-app"));
        var byPattern = Detectors.byPattern(new Detectors(app.code(), app.config()).all());

        assertThat(ScanCommand.prompt(app, RiskPattern.byId("P3").orElseThrow(), byPattern.get("P3"), null))
            .contains("class PaymentController").contains("UUID.randomUUID()");
        // the outline names every class; only consumer-side patterns get the senders' code
        assertThat(ScanCommand.prompt(app, RiskPattern.byId("P5").orElseThrow(), byPattern.get("P5"), null))
            .doesNotContain("UUID.randomUUID()");
    }

    @Test
    void settingsChangeTheConfigurationTheReviewJudges() throws Exception {
        var app = App.read(Path.of("../../demo-app"));
        var changed = app.withSettings(Map.of("payments.merchant-balance", "locked"));

        assertThat(app.config().get("payments.merchant-balance")).contains("read-then-write");
        assertThat(changed.config().get("payments.merchant-balance")).contains("locked");
        assertThat(ScanCommand.prompt(changed, RiskPattern.byId("P4").orElseThrow(),
            Detectors.byPattern(new Detectors(changed.code(), changed.config()).all()).get("P4"), null))
            .contains("payments.merchant-balance=locked");
    }
}
