package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.RequestShape;
import dev.faultline.cli.source.TestRepos;
import dev.faultline.cli.understand.AppMap;
import dev.faultline.cli.workload.Workload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plans for a repo whose topics are named in the code, whose listener replies with @SendTo, and whose paths carry IDs. */
class FormatsPlanTest {
    private static final AppMap MAP = new AppMap("ecommerce", "m", "Shop.", List.of(), List.of(), List.of(), List.of(), List.of());
    private static final Plan.Compose COMPOSE = new Plan.Compose("docker-compose.yml", null, List.of("cart", "order", "notification"), 1);
    private static final Deployment DEPLOYMENT = new Deployment(COMPOSE, "http://localhost:8080", List.of("order"), null, null,
        List.of(new Deployment.Service("cart", "cart", "http://localhost:8080", false), new Deployment.Service("order", "order", null, true),
            new Deployment.Service("notification", "notification", null, true)), null);

    private static Risk risk(String pattern, String location) {
        return new Risk("R1", pattern, "t", location, "f", 1, "high", "w");
    }

    @Test
    void aSendToReplyIsTestedByCrashingTheMomentTheListenerReturnsAndWatchingTheReplyTopic(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));

        var test = PlanCommand.aim(app, MAP, risk("P8", "OrderEventListener#receive"), DEPLOYMENT).test();

        assertThat(test.fault()).isEqualTo(new Plan.Fault(null, "OrderEventListener#receive", "after", "crash", 100L, 2, null, "order", null));
        assertThat(test.downstreamTopic()).isEqualTo("ecommerce-payments-in-topic");
        assertThat(test.checks()).contains(Plan.NO_LOST_DOWNSTREAM);
    }

    @Test
    void aTopicTheCodeNamesIsRelayedByTheAgentInTheServiceThatSendsToIt(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));

        var test = PlanCommand.aim(app, MAP, risk("P7", "OrderEventListener#receive"), DEPLOYMENT).test();

        assertThat(test.relay()).isEqualTo(new Plan.Relay("ecommerce-orders-topic.faultline", "ecommerce-orders-topic", List.of("cart")));
        assertThat(test.relay().rename()).isEqualTo("ecommerce-orders-topic>ecommerce-orders-topic.faultline");
        assertThat(test.settings()).isEmpty();
        assertThat(test.does()).contains("the agent renames the topic to ecommerce-orders-topic.faultline");
    }

    @Test
    void aRelaysSendersMustBeServicesOfTheStack() {
        var p = TestPlans.compose(new Plan.Fault("messaging", null, null, "corrupt", 300L, 1, null, null, null));
        var relayed = new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), p.kafka(), new Plan.Relay("t.faultline", "t", List.of("api", "mailer")),
            null, null, p.traffic(), p.correlateBy(), null, null, p.fault(), p.checks(), null, null, p.confirm());

        assertThat(relayed.problems()).containsExactly("relay.senders must be services of target.compose.services, not mailer");
    }

    @Test
    void aPathTakesItsVariablesFromTheBody() {
        assertThat(Workload.pathVariables("/cart/{userId}/items/{item.id}")).containsExactly("userId", "item.id");
        // a template's placeholder is no path variable
        assertThat(Workload.pathVariables("/cart/u-${seq:4}/items/{itemId}")).containsExactly("itemId");
        assertThat(Workload.pathOf("/cart/{userId}/items/{item.id}", "{\"userId\": \"u 1\", \"item\": {\"id\": 7}}")).isEqualTo("/cart/u%201/items/7");
        // a value the body lacks stays, encoded: the app answers 404 and the request counts as failed
        assertThat(Workload.pathOf("/cart/{userId}/checkout", "{}")).isEqualTo("/cart/%7BuserId%7D/checkout");
    }

    @Test
    void aPathWithItsIdsWrittenInStillNamesItsEndpoint() {
        assertThat(PlanCommand.withIdsIn("/cart/{userId}/checkout", "/cart/00000000-0000-0000-${seed:4}-${seq:12}/checkout")).isTrue();
        assertThat(PlanCommand.withIdsIn("/cart/{userId}/checkout", "/cart/{userId}/checkout/now")).isFalse();
        assertThat(PlanCommand.withIdsIn("/payments", "/payments")).isFalse();
    }

    @Test
    void aPlanWhosePathNamesAFieldTheBodyLacksIsRefused() {
        var p = TestPlans.valid();
        var t = p.traffic();
        var request = new Plan.Request("POST", "/cart/{userId}/checkout", t.request().body());
        var withPath = new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), p.kafka(), null, null, null,
            new Plan.Traffic(t.count(), t.rate(), t.seed(), request, t.drainTimeout()), p.correlateBy(), null, null, p.fault(), p.checks(), null, null,
            p.confirm());

        assertThat(withPath.problems()).containsExactly("traffic.request.path has {userId}, so the body needs a field userId: Faultline fills the path from it");
    }

    @Test
    void aBodyFieldThePathTakesIsNoFieldOfTheRequestBody() {
        var shape = new RequestShape("CartItemDto", List.of(new RequestShape.Field("productId", "string", false, List.of(), null, null, null, List.of(), null)));
        assertThat(PlanCommand.bodyProblems("{\"userId\": \"u-${seq}\"}", shape, List.of("userId"))).isEmpty();
    }
}
