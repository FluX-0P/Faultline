package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import java.nio.file.Path;
import java.util.List;

import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.TestRepos;
import dev.faultline.cli.understand.AppMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plans for a journey that goes through Kafka Connect and several consumer groups. */
class OutboxPlanTest {
    private static final AppMap MAP = new AppMap("ecommerce", "m", "Shop.", List.of(), List.of(), List.of(), List.of(), List.of());
    private static final Plan.Compose COMPOSE = new Plan.Compose("docker-compose.yml", null, List.of("connect", "cart", "order", "notification"), 1);
    private static final List<Deployment.Service> SERVICES = List.of(new Deployment.Service("cart", "cart", "http://localhost:8080", false),
        new Deployment.Service("order", "order", null, true), new Deployment.Service("notification", "notification", null, true));

    private static Risk risk(String pattern, String location) {
        return new Risk("R1", pattern, "t", location, "f", 1, "high", "w");
    }

    @Test
    void relyingOnKafkaConnectIsTestedByKillingItMidTrafficAndWatchingItsTopic(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));
        var deployment = new Deployment(COMPOSE, "http://localhost:8080", List.of("notification"), null, null, SERVICES, null, "connect");

        var test = PlanCommand.aim(app, MAP, risk("P9", "NotificationHandler#handleBatch"), deployment).test();

        assertThat(test.fault()).isEqualTo(new Plan.Fault("process", null, null, "kill", null, null, "20s", "connect", "10s"));
        assertThat(test.downstreamTopic()).isEqualTo("ecommerce-notifications-topic");
        assertThat(test.checks()).contains(Plan.NO_LOST_DOWNSTREAM);
    }

    @Test
    void withoutAKafkaConnectServiceThereIsNothingToKill(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));
        var deployment = new Deployment(COMPOSE, "http://localhost:8080", List.of("notification"), null, null, SERVICES, null);

        assertThatThrownBy(() -> PlanCommand.aim(app, MAP, risk("P9", "NotificationHandler#handleBatch"), deployment))
            .hasMessageContaining("takes the Kafka Connect service in the deployment");
    }

    @Test
    void theRunWaitsForEveryConsumerGroupOfTheServicesItRuns(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));
        var deployment = new Deployment(COMPOSE, "http://localhost:8080", List.of("order"), null, null, SERVICES, null);

        var groups = PlanCommand.otherGroups(app, deployment, "ecommerce-orders-topic", "ecommerce_group_id");

        // the order listener is the plan's own; notification's two listeners are others
        assertThat(groups).containsExactlyInAnyOrder(new Plan.Consumer("ecommerce-notifications-topic", "notifications"),
            new Plan.Consumer("ecommerce-audit-topic", "audit"));
        // a module no consumer service runs never drains, so it does not count
        var noNotification = new Deployment(COMPOSE, "http://localhost:8080", List.of("order"), null, null, SERVICES.subList(0, 2), null);
        assertThat(PlanCommand.otherGroups(app, noNotification, "ecommerce-orders-topic", "ecommerce_group_id")).isEmpty();
    }

    @Test
    void eachOtherGroupNeedsATopicAndAGroup() {
        var p = TestPlans.valid();
        var kafka = new Plan.Kafka(p.kafka().bootstrap(), p.kafka().topic(), p.kafka().group(), List.of(new Plan.Consumer("ledger.events", " ")));
        var plan = new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), kafka, null, null, null, p.traffic(), p.correlateBy(),
            null, null, p.fault(), p.checks(), null, null, p.confirm());

        assertThat(plan.problems()).containsExactly("each of kafka.groups needs topic and group");
    }
}
