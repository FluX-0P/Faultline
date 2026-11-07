package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.app.Environment;
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
    void aListenerOnlyKafkaConnectSendsToIsMovedBehindTheRelayWhenItsTopicIsASetting(@TempDir Path dir) throws Exception {
        var deployment = new Deployment(COMPOSE, "http://localhost:8080", List.of("notification"), null, null, SERVICES, null, "connect");
        // no code sends to its topic, which it names outright: nothing can be moved
        var fixed = App.read(TestRepos.ecommerce(dir.resolve("fixed")));
        assertThatThrownBy(() -> PlanCommand.aim(fixed, MAP, risk("P7", "NotificationHandler#handleBatch"), deployment))
            .hasMessageContaining("no service of the deployment sends to it");

        Path repo = TestRepos.ecommerce(dir.resolve("set"));
        Path handler = repo.resolve("notification/src/main/java/shop/notification/NotificationHandler.java");
        Files.writeString(handler, Files.readString(handler).replace("topics = \"ecommerce-notifications-topic\"", "topics = \"${notification.topic}\""));
        Files.writeString(repo.resolve("notification/src/main/resources/application.yml"), "notification:\n  topic: ecommerce-notifications-topic\n",
            StandardOpenOption.APPEND);

        var test = PlanCommand.aim(App.read(repo), MAP, risk("P7", "NotificationHandler#handleBatch"), deployment).test();

        // the relay reads Kafka Connect's topic and forwards to the one the listener now reads
        assertThat(test.relay()).isEqualTo(new Plan.Relay("ecommerce-notifications-topic", "ecommerce-notifications-topic.faultline"));
        assertThat(test.settings()).isEqualTo(Map.of("notification.topic", "ecommerce-notifications-topic.faultline"));
        assertThat(test.fault()).isEqualTo(new Plan.Fault("messaging", null, null, "corrupt", 300L, 1, null, null, null));
    }

    @Test
    void aFaultOnTheWayToTheListenerShowsOnlyInWhatTheListenersServiceWrites(@TempDir Path dir) throws Exception {
        var environment = new Environment("ecommerce", new Environment.Compose("docker-compose.yml", null, "ecommerce", null),
            List.of(new Environment.Service("cart", "cart", Map.of(), List.of(), false), new Environment.Service("order", "order", Map.of(), List.of(), true),
                new Environment.Service("notification", "notification", Map.of(), List.of(), true)),
            new Environment.Kafka("kafka", List.of("kafka:9092"), "localhost:39092", "a listener Faultline's override adds"),
            List.of(new Environment.Database("cart-db", "cart-db:5432", "cart", "localhost:15433", "cart", "cart", List.of("cart"),
                    List.of(new Environment.Table("cart_item", List.of(new Environment.Column("user_id", "uuid"), new Environment.Column("product_id", "uuid")), 0))),
                new Environment.Database("order-db", "order-db:5432", "order", "localhost:15434", "order", "order", List.of("order"),
                    List.of(new Environment.Table("orders", List.of(new Environment.Column("id", "uuid"), new Environment.Column("user_id", "uuid")), 0)))),
            List.of(), List.of(), "2026-11-07T00:00:00Z");
        var app = App.read(TestRepos.ecommerce(dir), environment);
        var deployment = new Deployment(COMPOSE, "http://localhost:8080", List.of("order"), null, null, SERVICES, null);
        var aim = PlanCommand.aim(app, MAP, risk("P7", "OrderEventListener#receive"), deployment);
        var facts = PlanCommand.facts(app, deployment, aim);
        var request = new PlanDraft.Request("POST", "/cart/{userId}/items", "{\"userId\": \"0000-${seed}-${seq}\", \"productId\": \"p-1\"}");
        var reset = List.of("TRUNCATE cart_item", "TRUNCATE orders");

        // the cart writes its row before it sends the message the relay corrupts: that row is there either way
        var cartRow = new PlanDraft("checkout", request, "userId", new PlanDraft.Effect("cart_item", "user_id"), reset, List.of(), "w");
        assertThat(PlanCommand.problems(cartRow, app, MAP, aim, facts, Map.of()))
            .anyMatch(p -> p.startsWith("effect.table 'cart_item' is written before the message reaches OrderEventListener#receive"));
        var orderRow = new PlanDraft("checkout", request, "userId", new PlanDraft.Effect("orders", "user_id"), reset, List.of(), "w");
        assertThat(PlanCommand.problems(orderRow, app, MAP, aim, facts, Map.of())).noneMatch(p -> p.startsWith("effect.table"));
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
