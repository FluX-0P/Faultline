package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.app.TestDeployments;
import dev.faultline.cli.benchmark.BenchmarkFile;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.TestApps;
import dev.faultline.cli.understand.AppMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultTemplateTest {
    private static final Path BENCHMARK = Path.of("../../benchmark/bugs.yaml");
    static final AppMap MAP = new AppMap("demo-app", "m", "Payments.", List.of(), List.of(), List.of(), List.of(),
        List.of(new AppMap.Journey("make-payment", "pay", List.of(new AppMap.Step("PaymentController#pay", "accept"),
            new AppMap.Step("PaymentListener#onMessage", "apply")))));

    private static Risk risk(String pattern, String location) {
        return new Risk("R1", pattern, "t", location, "f", 1, "high", "w");
    }

    @Test
    void eachPatternsTestIsTheOneItsHandWrittenBenchmarkPlanProved() throws Exception {
        var bench = BenchmarkFile.read(BENCHMARK);
        var app = App.read(TestDeployments.DEMO_APP);
        var deployment = TestDeployments.demo();
        for (var bug : bench.bugs()) {
            if (bug.pattern() == null) continue;
            Plan hand = Plan.read(BENCHMARK.resolveSibling(bug.plan()));
            var test = PlanCommand.aim(app.withSettings(bench.settings(bug)), MAP, risk(bug.pattern(), bug.location()), deployment).test();

            // B2's plan crashes inside the work the pool was handed; the template crashes as the listener returns
            if (!bug.id().equals("B2")) assertThat(test.fault()).as(bug.id()).isEqualTo(hand.fault());
            assertThat(test.checks()).as(bug.id()).isEqualTo(hand.checks());
            var t = hand.traffic();
            assertThat(test.traffic()).as(bug.id()).isEqualTo(new FaultTemplate.Traffic(t.count(), t.rate(), t.warmup(), t.drainTimeout()));
            assertThat(test.recoveryWithin()).as(bug.id()).isEqualTo(hand.recoveryWithin());
            assertThat(test.stub()).as(bug.id()).isEqualTo(hand.stub() != null);
        }
    }

    @Test
    void workHandedOffIsTestedByCrashingTheMomentTheListenerReturns() throws Exception {
        var test = PlanCommand.aim(App.read(TestDeployments.DEMO_APP), MAP, risk("P2", "PaymentListener#onMessage"), TestDeployments.demo()).test();

        assertThat(test.fault()).isEqualTo(new Plan.Fault(null, "PaymentListener#onMessage", "after", "crash", 150L, 2, null, "worker-1", null));
    }

    @Test
    void theRelaySitsInFrontOfTheListenerThroughTheSettingThatNamesTheTopicTheApiSendsTo() throws Exception {
        var test = PlanCommand.aim(App.read(TestDeployments.DEMO_APP), MAP, risk("P7", "TopicConfig#errorHandler"), TestDeployments.demo()).test();

        assertThat(test.relay()).isEqualTo(new Plan.Relay("payments.requested.faultline", "payments.requested"));
        assertThat(test.settings()).isEqualTo(Map.of("payments.inbound-topic", "payments.requested.faultline"));
    }

    @Test
    void savingThenPublishingIsCheckedOnTheTopicTheCodePublishesTo() throws Exception {
        var test = PlanCommand.aim(App.read(TestDeployments.DEMO_APP), MAP, risk("P8", "CompletedEvents#onCommitted"), TestDeployments.demo()).test();

        assertThat(test.downstreamTopic()).isEqualTo("payments.completed");
        assertThat(test.fault().point()).isEqualTo("CompletedEvents#publish");
    }

    @Test
    void theListenerIsTheOneTheTrafficArrivesAt() throws Exception {
        var app = App.read(TestDeployments.DEMO_APP);

        // the API sends to payments.requested, which onMessage reads; onRetry only gets what failed there
        assertThat(PlanCommand.listenerFor(app, MAP, risk("P5", "ProviderClient#charge")).handler()).isEqualTo("PaymentListener#onMessage");
        assertThat(PlanCommand.listenerFor(app, MAP, risk("P7", "TopicConfig#errorHandler")).handler()).isEqualTo("PaymentListener#onMessage");
        var onRetry = PlanCommand.aim(app, MAP, risk("P2", "PaymentListener#onRetry"), TestDeployments.demo());
        assertThat(onRetry.listener().handler()).isEqualTo("PaymentListener#onMessage");
        assertThat(onRetry.topic()).isEqualTo("payments.requested");
        assertThat(onRetry.test().fault().point()).isEqualTo("PaymentListener#onRetry");   // the fault still goes where the risk is
    }

    @Test
    void saysWhyARiskCannotBeTestedThisWay(@TempDir Path dir) throws Exception {
        var demo = App.read(TestDeployments.DEMO_APP);
        var jar = Deployment.jar("http://localhost:8080");

        assertThatThrownBy(() -> PlanCommand.aim(demo, MAP, risk("P4", "MerchantBalance#credit"), jar))
            .hasMessageContaining("at least two Kafka consumers");
        assertThatThrownBy(() -> PlanCommand.aim(demo, MAP, risk("P5", "ProviderClient#charge"), jar))
            .hasMessageContaining("fake provider");
        assertThatThrownBy(() -> PlanCommand.aim(demo, MAP, risk("P10", "ProviderClient#charge"), jar))
            .hasMessageContaining("no fault template for pattern P10");
        var fixedTopic = App.read(TestApps.payments(dir));
        assertThatThrownBy(() -> PlanCommand.aim(fixedTopic, MAP, risk("P7", "PaymentListener#onMessage"), jar))
            .hasMessageContaining("under a fixed name");
    }
}
