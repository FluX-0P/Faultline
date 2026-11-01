package dev.faultline.cli.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringConfigTest {

    @Test
    void theEnvironmentSetsKeysTheWaySpringBindsThem(@TempDir Path dir) throws Exception {
        var files = RepoFiles.scan(TestApps.payments(dir));

        var config = SpringConfig.load(files, Map.of(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/pay",
            "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092",
            "SPRING_KAFKA_CONSUMER_GROUPID", "from-env",
            "SERVER_PORT", "8080"));

        assertThat(config.get("spring.datasource.url")).contains("jdbc:postgresql://postgres:5432/pay");
        // a dashed key the files do not set, in the older form with underscores, and one the files set, without the dash
        assertThat(config.get("spring.kafka.bootstrap-servers")).contains("kafka:9092");
        assertThat(config.get("spring.kafka.consumer.group-id")).contains("from-env");
        assertThat(config.get("server.port")).contains("8080");
        assertThat(config.asText()).doesNotContain("spring.kafka.bootstrap.servers");
    }

    @Test
    void theActiveProfilesFilesAndDocumentsGoOnTopOfTheBase(@TempDir Path dir) throws Exception {
        write(dir, "src/main/resources/application.yml", """
            spring:
              datasource:
                url: jdbc:postgresql://localhost/base
            app:
              mode: base
            ---
            spring:
              config:
                activate:
                  on-profile: prod
            app:
              mode: prod-document
            """);
        write(dir, "src/main/resources/application-prod.yml", "spring:\n  datasource:\n    url: jdbc:postgresql://db/prod\n");
        write(dir, "src/main/resources/application-dev.properties", "app.mode=dev\n");
        var files = RepoFiles.scan(dir);

        var asBuilt = SpringConfig.load(files);
        assertThat(asBuilt.get("app.mode")).contains("base");
        assertThat(asBuilt.get("spring.datasource.url")).contains("jdbc:postgresql://localhost/base");

        var prod = SpringConfig.load(files, Map.of("SPRING_PROFILES_ACTIVE", "prod"));
        assertThat(prod.get("app.mode")).contains("prod-document");
        assertThat(prod.get("spring.datasource.url")).contains("jdbc:postgresql://db/prod");

        // later profiles win, and the environment wins over every file
        var both = SpringConfig.load(files, Map.of("SPRING_PROFILES_ACTIVE", "prod,dev", "APP_MODE", "from-env"));
        assertThat(both.get("app.mode")).contains("from-env");
        assertThat(SpringConfig.load(files, Map.of("SPRING_PROFILES_ACTIVE", "prod,dev")).get("app.mode")).contains("dev");
    }

    @Test
    void placeholdersInsideAValueResolveFromTheEnvironmentThenTheirDefaults(@TempDir Path dir) throws Exception {
        write(dir, "src/main/resources/application.yml", """
            spring:
              datasource:
                url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:outbox}
              kafka:
                bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
            app:
              topic: ${app.prefix}.orders
              prefix: shop
            """);
        var files = RepoFiles.scan(dir);

        var running = SpringConfig.load(files, Map.of("DB_HOST", "postgres", "KAFKA_BOOTSTRAP", "kafka:29092"));
        assertThat(running.get("spring.datasource.url")).contains("jdbc:postgresql://postgres:5432/outbox");
        assertThat(running.get("spring.kafka.bootstrap-servers")).contains("kafka:29092");
        assertThat(running.get("app.topic")).contains("shop.orders");
        // a variable no file names still reaches the code, as @Value("${DB_HOST}") would
        assertThat(running.get("DB_HOST")).contains("postgres");

        assertThat(SpringConfig.load(files).get("spring.datasource.url")).contains("jdbc:postgresql://localhost:5432/outbox");
    }

    @Test
    void springApplicationJsonWinsOverTheOtherVariables(@TempDir Path dir) throws Exception {
        var files = RepoFiles.scan(TestApps.payments(dir));

        var config = SpringConfig.load(files, Map.of("SERVER_PORT", "9000", "SPRING_APPLICATION_JSON", "{\"server\":{\"port\":9100},\"app\":{\"topic\":\"x\"}}"));

        assertThat(config.get("server.port")).contains("9100");
        assertThat(config.get("app.topic")).contains("x");
    }

    private static void write(Path dir, String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
