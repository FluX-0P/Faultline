package dev.faultline.cli.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.faultline.cli.scan.Detectors.Candidate;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.TestRepos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Listeners shaped other than the demo's: a batch, a @SendTo reply, a message inside a ConsumerRecord. */
class ListenerShapesTest {
    private static List<String> found(List<Candidate> candidates) {
        return candidates.stream().map(c -> c.method().handler() + ": " + c.saw()).toList();
    }

    @Test
    void aBatchListenersWorkIsFoundThroughItsMethodForOneRecord(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));

        var p3 = found(Detectors.of(app).p3());

        assertThat(p3).contains("NotificationService#process: the method the Kafka listener NotificationHandler#handleBatch calls to do its work");
        assertThat(found(Detectors.of(app).p7())).contains("NotificationHandler#handleBatch: a batch Kafka listener (the container commits the whole "
            + "batch once it returns), and the app configures no error handling of its own");
    }

    @Test
    void aListenerThatWritesAndRepliesWithSendToSavesThenPublishes(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));

        var p8 = found(Detectors.of(app).p8());

        assertThat(p8).containsExactly("OrderEventListener#receive: a Kafka listener that writes orders and returns a reply, "
            + "which @SendTo(ecommerce-payments-in-topic) publishes once it has returned");
        assertThat(Detectors.of(app).publishers()).extracting(m -> m.handler()).contains("OrderEventListener#receive", "OrderEventSender#send");
    }

    @Test
    void theReviewerSeesTheMessageAListenerTakesEvenInsideABatchOfRecords(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));
        var p3 = Detectors.of(app).p3().stream().filter(c -> c.method().handler().equals("NotificationService#process")).toList();

        String prompt = ScanCommand.prompt(app, RiskPattern.byId("P3").orElseThrow(), p3, null);

        assertThat(prompt).contains("public record NotificationEvent(String eventId, String userId)");
    }

    @Test
    void aListenerOnATopicNoCodeSendsToIsP9OnlyWhenTheAppRunsKafkaConnect(@TempDir Path dir) throws Exception {
        Path repo = TestRepos.ecommerce(dir);
        assertThat(Detectors.of(App.read(repo)).p9()).isEmpty();

        Files.writeString(repo.resolve("docker-compose.yml"), """
            services:
              connect:
                image: quay.io/debezium/connect:2.7.3.Final
            """);

        assertThat(found(Detectors.of(App.read(repo)).p9())).containsExactlyInAnyOrder(
            "NotificationHandler#handleBatch: a batch Kafka listener (the container commits the whole batch once it returns) on ecommerce-notifications-topic, "
                + "which no code of the app sends to: Kafka Connect fills it, and delivers at least once",
            "AuditListener#onAudit: a Kafka listener on ecommerce-audit-topic, which no code of the app sends to: Kafka Connect fills it, and delivers at least once");
    }
}
