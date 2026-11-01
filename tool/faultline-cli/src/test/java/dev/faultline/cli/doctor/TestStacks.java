package dev.faultline.cli.doctor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A repo shaped like ewolff/microservice-kafka on disk: an order module that takes HTTP, a shipping module that
 * listens on Kafka, a Postgres image built from its own Dockerfile and an Apache proxy in front, with the Compose
 * config {@code docker compose config --format json} prints for it.
 */
final class TestStacks {
    private TestStacks() {}

    static Path shop(Path dir) throws IOException {
        write(dir, "README.md", "# Shop\n");
        write(dir, "shop/pom.xml", "<project><modules><module>order</module><module>shipping</module></modules></project>\n");
        write(dir, "shop/mvnw", "#!/bin/sh\n");
        write(dir, "shop/order/pom.xml", """
            <project>
              <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.0.0</version></parent>
              <properties><java.version>17</java.version></properties>
              <dependencies><dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency></dependencies>
            </project>
            """);
        write(dir, "shop/order/Dockerfile", "FROM eclipse-temurin:17-jre\nCOPY target/order-0.0.1-SNAPSHOT.jar .\nCMD java -jar order-0.0.1-SNAPSHOT.jar\n");
        write(dir, "shop/order/src/main/resources/application.properties", """
            spring.datasource.url=jdbc:postgresql://postgres/dborder
            spring.datasource.username=dbuser
            spring.datasource.password=dbpass
            """);
        write(dir, "shop/order/src/main/java/order/OrderController.java", """
            package order;
            @RestController
            class OrderController {
                @PostMapping("/orders") Object post(Order order) { return null; }
            }
            """);
        write(dir, "shop/shipping/pom.xml", "<project><artifactId>shipping</artifactId></project>\n");
        write(dir, "shop/shipping/Dockerfile", "FROM eclipse-temurin:17-jre\nCOPY target/shipping-*.jar app.jar\n");
        write(dir, "shop/shipping/target/shipping-0.0.1-SNAPSHOT.jar", "jar");
        write(dir, "shop/shipping/src/main/resources/application.properties", """
            server.port=8080
            spring.kafka.consumer.group-id=shipping
            spring.datasource.url=jdbc:postgresql://postgres/dbshipping
            spring.datasource.username=dbuser
            spring.datasource.password=${DB_PASSWORD:dbpass}
            """);
        write(dir, "shop/shipping/src/main/java/shipping/ShipmentListener.java", """
            package shipping;
            class ShipmentListener {
                @KafkaListener(topics = "order")
                void onOrder(String order, Acknowledgment ack) { ack.acknowledge(); }
            }
            """);
        write(dir, "docker/postgres/Dockerfile", "FROM postgres:15.3\nCOPY init-user-db.sh /docker-entrypoint-initdb.d/\n");
        write(dir, "docker/apache/Dockerfile", "FROM httpd:2.4\n");
        return dir;
    }

    /**
     * What docker compose config prints for the shop's Compose file. Shipping's DB_PASSWORD comes from the shell, so
     * {@code interpolated} false shows it as ${DB_PASSWORD}.
     */
    static String config(Path dir, boolean interpolated) {
        return """
            {
              "name": "shop",
              "services": {
                "apache": {"build": {"context": "%s", "dockerfile": "Dockerfile"}, "ports": [{"target": 80, "published": "8080"}],
                           "depends_on": {"order": {"condition": "service_started"}}},
                "kafka": {"image": "wurstmeister/kafka:2.13-2.8.1", "depends_on": {"zookeeper": {}},
                          "environment": {"KAFKA_ADVERTISED_HOST_NAME": "kafka", "KAFKA_ADVERTISED_PORT": "9092",
                                          "KAFKA_ZOOKEEPER_CONNECT": "zookeeper:2181", "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "false"}},
                "zookeeper": {"image": "wurstmeister/zookeeper:3.4.6"},
                "postgres": {"build": {"context": "%s", "dockerfile": "Dockerfile"},
                             "environment": {"POSTGRES_USER": "dbuser", "POSTGRES_PASSWORD": "dbpass"}},
                "order": {"build": {"context": "%s", "dockerfile": "Dockerfile"}, "links": ["kafka", "postgres"],
                          "depends_on": {"kafka": {}, "postgres": {}}, "ports": [{"target": 8080, "published": "8081"}],
                          "environment": {"SPRING_KAFKA_BOOTSTRAP_SERVERS": "kafka:9092"}},
                "shipping": {"build": {"context": "%s", "dockerfile": "Dockerfile"}, "links": ["kafka", "postgres:db"],
                             "depends_on": {"kafka": {}, "postgres": {}}, "ports": [{"target": 8080, "published": "8082"}],
                             "environment": {"SPRING_KAFKA_BOOTSTRAP_SERVERS": "kafka:9092", "DB_PASSWORD": "%s"}}
              }
            }
            """.formatted(json(dir.resolve("docker/apache")), json(dir.resolve("docker/postgres")), json(dir.resolve("shop/order")),
            json(dir.resolve("shop/shipping")), interpolated ? "from-the-shell" : "${DB_PASSWORD}");
    }

    private static String json(Path p) {
        return p.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }

    private static void write(Path dir, String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
