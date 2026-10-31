package dev.faultline.cli.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.util.Map;

import dev.faultline.cli.source.RepoFiles.TextFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppReadingTest {

    @Test
    void readsDocsBuildFilesAndMigrationsButNotTestsOrBuildOutput(@TempDir Path dir) throws Exception {
        var files = RepoFiles.scan(TestApps.payments(dir));

        assertThat(files.docs()).extracting(TextFile::path).containsExactly("README.md");
        assertThat(files.buildFiles()).extracting(TextFile::path).containsExactly("pom.xml");
        assertThat(files.javaSources()).extracting(files::relative).noneMatch(p -> p.contains("src/test/"));
        assertThat(files.javaSources()).hasSize(5);
        // the schema without its comments: the planted-bug note must not reach the LLM
        assertThat(files.migrations()).singleElement().extracting(TextFile::text)
            .isEqualTo("create table payments (payment_id text not null);\n");
    }

    @Test
    void flattensSpringConfigAndResolvesPlaceholders(@TempDir Path dir) throws Exception {
        var config = SpringConfig.load(RepoFiles.scan(TestApps.payments(dir)));

        assertThat(config.get("spring.datasource.url")).contains("jdbc:postgresql://localhost:5432/pay");
        assertThat(config.get("spring.kafka.consumer.properties[session.timeout.ms]")).contains("6000");
        assertThat(config.get("server.port")).contains("8081");
        assertThat(config.asText()).doesNotContain("the local database");
        assertThat(new SpringConfig(Map.of("a", "${A_URL:http://x}")).get("a")).contains("http://x");
    }

    @Test
    void findsEndpointsWithResolvedPathsTopicsAndGroups(@TempDir Path dir) throws Exception {
        var app = App.read(TestApps.payments(dir));

        assertThat(app.code().endpoints()).extracting(Endpoint::name, Endpoint::handler, Endpoint::groupId).containsExactlyInAnyOrder(
            tuple("POST /api/payments", "PaymentController#pay", null),
            tuple("GET /api/payments/{id}", "PaymentController#get", null),
            tuple("GET /api/p/{id}", "PaymentController#get", null),
            // Topics.IN is "${app.topic}", which the config sets to payments.in
            tuple("kafka payments.in", "PaymentListener#onMessage", null),
            tuple("kafka audit", "PaymentListener#audit", "auditors"));
    }

    @Test
    void findsP1CandidatesAndTheClassesTheyCall(@TempDir Path dir) throws Exception {
        var code = App.read(TestApps.payments(dir)).code();

        var candidates = code.kafkaListenersThatAcknowledge();
        assertThat(candidates).extracting(JavaSources.Method::handler).containsExactly("PaymentListener#onMessage");
        assertThat(code.calledTypes(candidates.getFirst())).extracting(t -> t.getNameAsString()).containsExactly("PaymentService");
        assertThat(code.requestBodyTypes(code.method("PaymentController#pay").orElseThrow()))
            .extracting(t -> t.getNameAsString()).containsExactly("PaymentRequest");
    }

    @Test
    void printsCodeWithoutComments(@TempDir Path dir) throws Exception {
        var code = App.read(TestApps.payments(dir)).code();

        String listener = code.source(code.type("PaymentListener").orElseThrow());
        String service = code.source(code.type("PaymentService").orElseThrow());
        assertThat(listener).contains("ack.acknowledge();").doesNotContain("B1").doesNotContain("planted");
        assertThat(service).contains("insert into payments").doesNotContain("Applies one payment");
        assertThat(code.outline()).contains("record PaymentRequest(String paymentId, long amount)")
            .contains("@PostMapping(\"/payments\") ResponseEntity<Void> pay(@RequestBody PaymentRequest req)");
    }
}
