package dev.faultline.cli.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.faultline.cli.plan.Hints;
import dev.faultline.cli.source.TestRepos;

/** A repo of several Spring Boot apps: which service runs which module, and the deployment as one journey sees it. */
class ServicesDeploymentTest {
    @TempDir
    Path dir;

    private static final List<Deployment.Module> MODULES = List.of(new Deployment.Module("invoicing", 8082, true),
        new Deployment.Module("order", 8080, false), new Deployment.Module("shipping", 8081, true));

    /** order builds from the repo with its Dockerfile in its folder, shipping from its folder, invoicing from a root Dockerfile that copies its jar. */
    private String config(Path repo) throws Exception {
        Files.writeString(repo.resolve("Dockerfile.invoicing"), "FROM eclipse-temurin:21-jre\nCOPY invoicing/target/invoicing-*.jar /app/app.jar\n");
        String root = path(repo);
        return """
            {"name": "shop", "services": {
              "connect": {"image": "quay.io/debezium/connect:3.1", "ports": [{"target": 8083, "published": "8085"}]},
              "invoicing": {"build": {"context": "%1$s", "dockerfile": "Dockerfile.invoicing"}, "ports": [{"target": 8082, "published": "9082"}]},
              "kafka": {"image": "apache/kafka:4.1.0"},
              "order": {"build": {"context": "%1$s", "dockerfile": "order/Dockerfile"}, "image": "shop/order",
                        "environment": {"SPRING_DATA_REDIS_HOST": "redis"}, "ports": [{"target": 8080, "published": "9080"}]},
              "order-2": {"image": "shop/order"},
              "redis": {"image": "redis:7.2-alpine"},
              "shipping": {"build": {"context": "%1$s/shipping", "dockerfile": "Dockerfile"}, "ports": [{"target": 8081, "published": "9081"}]}
            }}""".formatted(root);
    }

    @Test
    void eachServiceRunsTheModuleItsBuildNames() throws Exception {
        Path repo = TestRepos.shop(dir);
        var d = Deployment.parse(config(repo), "compose.yml", null, null, repo, MODULES, Hints.NONE);

        assertThat(d.services()).extracting(Deployment.Service::name, Deployment.Service::module).containsExactly(
            org.assertj.core.groups.Tuple.tuple("invoicing", "invoicing"), org.assertj.core.groups.Tuple.tuple("order", "order"),
            org.assertj.core.groups.Tuple.tuple("order-2", "order"), org.assertj.core.groups.Tuple.tuple("shipping", "shipping"));
        // order has no Kafka listener, so it consumes nothing; Kafka Connect is part of the journey and recreated with it
        assertThat(d.consumers()).containsExactly("invoicing", "shipping");
        assertThat(d.compose().services()).containsExactly("connect", "invoicing", "order", "order-2", "shipping");
        assertThat(d.compose().connect()).isEqualTo("http://localhost:8085");
        assertThat(d.compose().flushed()).containsExactly("redis");
        assertThat(d.url()).isEqualTo("http://localhost:9080");
    }

    @Test
    void aJourneyTakesItsApiFromTheEntryAndItsConsumersFromTheListener() throws Exception {
        Path repo = TestRepos.shop(dir);
        var d = Deployment.parse(config(repo), "compose.yml", null, null, repo, MODULES, Hints.NONE);

        var journey = d.forJourney("order", "shipping", "shipping");
        assertThat(journey.url()).isEqualTo("http://localhost:9080");
        assertThat(journey.consumers()).containsExactly("shipping");
        assertThat(journey.compose().consumers()).isEqualTo(1);
        assertThat(journey.faultTarget()).isEqualTo("shipping");
        // the risk in the module that takes the HTTP requests: the fault goes on its service
        assertThat(d.forJourney(null, "shipping", "order").faultTarget()).isEqualTo("order");
        assertThat(d.urlOf("shipping")).contains("http://localhost:9081");
    }

    @Test
    void theHintsNameTheModuleOfAServiceItsBuildDoesNotSay() throws Exception {
        Path repo = TestRepos.shop(dir);
        String config = config(repo).replace("\"kafka\": {\"image\": \"apache/kafka:4.1.0\"}",
            "\"billing\": {\"image\": \"shop/billing\"}, \"kafka\": {\"image\": \"apache/kafka:4.1.0\"}");
        var hints = new Hints(null, null, null, null, List.of("order-2"), Map.of("billing", "invoicing"));

        var d = Deployment.parse(config, "compose.yml", null, null, repo, MODULES, hints);
        assertThat(d.servicesOf("invoicing")).containsExactly("invoicing", "billing");
        assertThat(d.servicesOf("order")).containsExactly("order");

        var wrong = new Hints(null, null, null, null, null, Map.of("billing", "payments"));
        assertThatThrownBy(() -> Deployment.parse(config, "compose.yml", null, null, repo, MODULES, wrong))
            .hasMessageContaining("billing runs payments, which is no Spring Boot app of the repo");
    }

    @Test
    void aDeploymentOfOneModuleIsTheSameForEveryJourney() throws Exception {
        var demo = TestDeployments.demo();

        assertThat(demo.forJourney("", "", "")).isSameAs(demo);
        assertThat(demo.compose().connect()).isNull();
        assertThat(demo.compose().flush()).isNull();
    }

    private static String path(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
