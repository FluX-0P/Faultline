package dev.faultline.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentTest {

    /** Two modules on one Postgres server with a database each, and Kafka through a listener of Faultline's. */
    static final Environment SHOP = new Environment("shop", new Environment.Compose("docker/docker-compose.yml", null, "shop", ".faultline/shop/compose.faultline.yml"),
        List.of(new Environment.Service("order", "shop/order", Map.of("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"), List.of(), false),
            new Environment.Service("shipping-2", "shop/shipping", Map.of("SERVER_PORT", "8082"), List.of(), true),
            new Environment.Service("shipping-1", "shop/shipping", Map.of("SERVER_PORT", "8081"), List.of(), true),
            new Environment.Service("shipping-api", "shop/shipping", Map.of("SERVER_PORT", "8080"), List.of(), false)),
        new Environment.Kafka("kafka", List.of("kafka:9092"), "localhost:39092", "a listener Faultline's override adds"),
        List.of(
            new Environment.Database("postgres", "postgres:5432", "dborder", "localhost:15432", "dbuser", "dbpass", List.of("order"),
                List.of(new Environment.Table("ordertable", List.of(new Environment.Column("id", "bigint"), new Environment.Column("customer_id", "bigint")), 0),
                    new Environment.Table("customer", List.of(new Environment.Column("ID", "bigint")), 3))),
            new Environment.Database("postgres", "postgres:5432", "dbshipping", "localhost:15432", "dbuser", null, List.of("shipping-1", "shipping-2", "shipping-api"),
                List.of(new Environment.Table("audit.shipment", List.of(new Environment.Column("id", "bigint")), 0)))),
        List.of(new Environment.Topic("order", 5)), List.of("shipping"), "2026-11-01T00:00:00Z");

    @Test
    void anInDockerAddressBecomesTheOneOnThisMachine() {
        assertThat(SHOP.hostJdbcUrl("jdbc:postgresql://postgres:5432/dborder", "")).isEqualTo("jdbc:postgresql://localhost:15432/dborder");
        assertThat(SHOP.hostJdbcUrl("jdbc:postgresql://postgres/dbshipping?ssl=false", "")).isEqualTo("jdbc:postgresql://localhost:15432/dbshipping?ssl=false");
        // through a proxy Faultline does not know: the module's database of that name
        assertThat(SHOP.hostJdbcUrl("jdbc:postgresql://toxiproxy:15431/dborder", "shop/order")).isEqualTo("jdbc:postgresql://localhost:15432/dborder");
        assertThat(SHOP.hostJdbcUrl("jdbc:postgresql://elsewhere/other", "")).isEqualTo("jdbc:postgresql://elsewhere/other");
        assertThat(SHOP.hostJdbcUrl("jdbc:h2:mem:test", "")).isEqualTo("jdbc:h2:mem:test");

        assertThat(SHOP.hostBootstrap("kafka:9092")).isEqualTo("localhost:39092");
        assertThat(SHOP.hostBootstrap("PLAINTEXT://kafka:9092")).isEqualTo("localhost:39092");
        assertThat(SHOP.hostBootstrap("localhost:9092")).isEqualTo("localhost:9092");
    }

    @Test
    void theSchemaAndSeededTablesAreTheModulesDatabases() {
        assertThat(SHOP.schemaOf("shop/order")).containsOnlyKeys("ordertable", "customer");
        assertThat(SHOP.schemaOf("shop/order").get("customer")).containsExactly("id");
        assertThat(SHOP.schemaOf("")).containsOnlyKeys("ordertable", "customer", "audit.shipment");
        assertThat(SHOP.seededTablesOf("")).containsExactly("customer");
        assertThat(SHOP.describeSchemaOf("shop/order")).contains("table ordertable (id bigint, customer_id bigint) -- 0 rows after the app started");
    }

    @Test
    void aModuleIsJudgedByAConsumerThatRunsIt() {
        assertThat(SHOP.runtimeOf("shop/shipping")).containsEntry("SERVER_PORT", "8081");
        assertThat(SHOP.runtimeOf("shop/order")).containsEntry("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        assertThat(SHOP.runtimeOf("")).isEmpty();
        assertThat(SHOP.servicesIn("shop")).containsExactly("order", "shipping-1", "shipping-2", "shipping-api");
    }

    @Test
    void readsBackWhatItWrote(@TempDir Path dir) {
        Json.writePretty(dir.resolve(Environment.FILE), SHOP);

        assertThat(Environment.read(dir)).contains(SHOP);
        assertThat(Environment.read(dir.resolve("nothing"))).isEmpty();
    }
}
