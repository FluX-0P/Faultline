package dev.faultline.cli.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A repo with three Spring Boot apps, shaped like ewolff's microservice-kafka: order takes orders over HTTP and sends
 * them to Kafka, shipping and invoicing each read them (both with a class named OrderListener), and common is a
 * library they share. Each app has its own configuration and database.
 */
public final class TestRepos {
    private TestRepos() {}

    public static Path shop(Path dir) throws IOException {
        write(dir, "README.md", "# Shop\n\nOrders, shipped and invoiced.\n");
        write(dir, "pom.xml", "<project><modules><module>order</module><module>shipping</module><module>invoicing</module><module>common</module></modules></project>\n");
        write(dir, "common/pom.xml", "<project><artifactId>common</artifactId></project>\n");
        write(dir, "common/src/main/java/shop/common/Money.java", """
            package shop.common;
            public record Money(long cents) {}
            """);

        app(dir, "order", 8080, "order-db/order", null);
        write(dir, "order/src/main/java/shop/order/OrderController.java", """
            package shop.order;
            @RestController
            class OrderController {
                private OrderService service;
                @PostMapping("/orders")
                public Order place(@RequestBody Order order) {
                    return service.place(order);
                }
            }
            """);
        write(dir, "order/src/main/java/shop/order/Order.java", """
            package shop.order;
            public record Order(String customer, int count) {}
            """);
        write(dir, "order/src/main/java/shop/order/OrderService.java", """
            package shop.order;
            @Service
            class OrderService {
                private KafkaTemplate<String, Order> kafka;
                private JdbcTemplate jdbc;
                @Value("${shop.topic:orders}")
                private String topic;
                Order place(Order order) {
                    jdbc.update("insert into orders (customer, count) values (?, ?)", order.customer(), order.count());
                    kafka.send(topic, order.customer(), order);
                    return order;
                }
            }
            """);

        app(dir, "shipping", 8081, "shipping-db/shipping", "shipping");
        write(dir, "shipping/src/main/java/shop/shipping/OrderListener.java", """
            package shop.shipping;
            @Component
            class OrderListener {
                private ShipmentService service;
                @KafkaListener(topics = "${shop.topic}")
                public void onOrder(Shipment shipment) {
                    service.ship(shipment);
                }
            }
            """);
        write(dir, "shipping/src/main/java/shop/shipping/Shipment.java", """
            package shop.shipping;
            public record Shipment(String customer) {}
            """);
        write(dir, "shipping/src/main/java/shop/shipping/ShipmentService.java", """
            package shop.shipping;
            @Service
            class ShipmentService {
                private JdbcTemplate jdbc;
                void ship(Shipment shipment) {
                    jdbc.update("insert into shipment (customer) values (?)", shipment.customer());
                }
            }
            """);

        app(dir, "invoicing", 8082, "invoicing-db/invoicing", "invoicing");
        write(dir, "invoicing/src/main/java/shop/invoicing/OrderListener.java", """
            package shop.invoicing;
            @Component
            class OrderListener {
                private InvoiceService service;
                @KafkaListener(topics = "${shop.topic}")
                public void onOrder(Invoice invoice) {
                    service.bill(invoice);
                }
            }
            """);
        write(dir, "invoicing/src/main/java/shop/invoicing/Invoice.java", """
            package shop.invoicing;
            public record Invoice(String customer) {}
            """);
        write(dir, "invoicing/src/main/java/shop/invoicing/InvoiceService.java", """
            package shop.invoicing;
            @Service
            class InvoiceService {
                private JdbcTemplate jdbc;
                void bill(Invoice invoice) {
                    jdbc.update("insert into invoice (customer) values (?)", invoice.customer());
                }
            }
            """);
        return dir;
    }

    /** A module: its build file, its @SpringBootApplication class and its configuration (no consumer group without {@code group}). */
    private static void app(Path dir, String name, int port, String database, String group) throws IOException {
        String app = Character.toUpperCase(name.charAt(0)) + name.substring(1) + "App";
        write(dir, name + "/pom.xml", "<project><artifactId>" + name + "</artifactId><version>1.0.0</version></project>\n");
        write(dir, name + "/src/main/java/shop/" + name + "/" + app + ".java", """
            package shop.%s;
            @SpringBootApplication
            public class %s {}
            """.formatted(name, app));
        write(dir, name + "/src/main/resources/application.yml", """
            server:
              port: %d
            spring:
              datasource:
                url: jdbc:postgresql://%s
                username: %s
                password: %s
            %s
            shop:
              topic: orders
            """.formatted(port, database, name, name, group == null ? "" : "  kafka:\n    consumer:\n      group-id: " + group));
    }

    private static void write(Path dir, String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
