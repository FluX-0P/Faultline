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

    /**
     * A repo shaped like ionutbalosin's ecommerce-app and KHolodilin's outbox: cart's controllers are generated from
     * cart-api's OpenAPI spec at build time, so only the spec has their paths, and checkout sends to a topic the code
     * names; order's listener replies with @SendTo; notification takes its messages in batches, with the work for each
     * record in a method of its own, and has a class-level @KafkaListener with @KafkaHandler methods.
     */
    public static Path ecommerce(Path dir) throws IOException {
        write(dir, "pom.xml", "<project><modules><module>cart-api</module><module>cart</module><module>order</module><module>notification</module></modules></project>\n");
        write(dir, "cart-api/pom.xml", "<project><artifactId>cart-api</artifactId><build><plugins><plugin><artifactId>openapi-generator-maven-plugin</artifactId>"
            + "</plugin></plugins></build></project>\n");
        write(dir, "cart-api/src/main/resources/service-api.yaml", """
            openapi: 3.0.3
            info:
              title: Cart
              version: "1.0"
            paths:
              /cart/{userId}/items:
                post:
                  parameters:
                    - in: path
                      name: userId
                      required: true
                      schema:
                        type: string
                  requestBody:
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/CartItemDto'
                  responses:
                    "201":
                      description: Created
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/CartDto'
              /cart/{userId}/checkout:
                post:
                  responses:
                    "202":
                      description: Accepted
              /cart/{userId}/coupons:
                put:
                  operationId: apply-coupon
                  responses:
                    "200":
                      description: OK
            components:
              schemas:
                CartItemDto:
                  type: object
                  required: [productId]
                  properties:
                    productId:
                      type: string
                      format: uuid
                    quantity:
                      type: integer
                      minimum: 1
                CartDto:
                  type: object
                  properties:
                    cartId:
                      type: string
                    owner:
                      type: object
                      properties:
                        userId:
                          type: string
            """);

        app(dir, "cart", 8080, "cart-db/cart", null);
        write(dir, "cart/src/main/java/shop/cart/CartController.java", """
            package shop.cart;
            @Controller
            public class CartController implements CartApi {
                private CartService service;
                @Override
                public ResponseEntity<CartDto> cartUserIdItemsPost(UUID userId, CartItemDto item) {
                    return new ResponseEntity<>(service.add(userId, item), CREATED);
                }
                @Override
                public ResponseEntity<Void> cartUserIdCheckoutPost(UUID userId) {
                    service.checkout(userId);
                    return new ResponseEntity<>(ACCEPTED);
                }
            }
            """);
        write(dir, "cart/src/main/java/shop/cart/CartService.java", """
            package shop.cart;
            @Service
            class CartService {
                private OrderEventSender sender;
                private JdbcTemplate jdbc;
                CartDto add(UUID userId, CartItemDto item) {
                    jdbc.update("insert into cart_item (user_id, product_id) values (?, ?)", userId, item.getProductId());
                    return null;
                }
                void checkout(UUID userId) {
                    sender.send(new OrderCreatedEvent(userId));
                }
            }
            """);
        write(dir, "cart/src/main/java/shop/cart/OrderEventSender.java", """
            package shop.cart;
            @Component
            class OrderEventSender {
                public static final String ORDERS_TOPIC = "ecommerce-orders-topic";
                private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
                void send(OrderCreatedEvent event) {
                    kafkaTemplate.send(ORDERS_TOPIC, event);
                }
            }
            """);

        app(dir, "order", 8081, "order-db/order", null);
        write(dir, "order/src/main/java/shop/order/OrderEventListener.java", """
            package shop.order;
            @Service
            public class OrderEventListener {
                public static final String ORDERS_TOPIC = "ecommerce-orders-topic";
                public static final String PAYMENTS_IN_TOPIC = "ecommerce-payments-in-topic";
                private OrderService orderService;
                @KafkaListener(topics = ORDERS_TOPIC, groupId = "ecommerce_group_id")
                @SendTo(PAYMENTS_IN_TOPIC)
                public PaymentTriggerCommand receive(@Payload OrderCreatedEvent orderEvent) {
                    UUID orderId = orderService.createOrder(orderEvent);
                    return new PaymentTriggerCommand(orderId);
                }
            }
            """);
        write(dir, "order/src/main/java/shop/order/OrderService.java", """
            package shop.order;
            @Service
            class OrderService {
                private OrderRepository repository;
                UUID createOrder(OrderCreatedEvent event) {
                    return repository.insert(event);
                }
            }
            """);
        write(dir, "order/src/main/java/shop/order/OrderRepository.java", """
            package shop.order;
            @Repository
            class OrderRepository {
                private JdbcTemplate jdbc;
                UUID insert(OrderCreatedEvent event) {
                    jdbc.update("insert into orders (id, user_id) values (?, ?)", UUID.randomUUID(), event.userId());
                    return null;
                }
            }
            """);
        write(dir, "order/src/main/java/shop/order/OrderCreatedEvent.java", """
            package shop.order;
            public record OrderCreatedEvent(UUID userId) {}
            """);

        app(dir, "notification", 8082, "notification-db/notification", "notifications");
        write(dir, "notification/src/main/java/shop/notification/NotificationHandler.java", """
            package shop.notification;
            @Component
            public class NotificationHandler {
                private Tracing tracing;
                private NotificationService service;
                @KafkaListener(topics = "ecommerce-notifications-topic", containerFactory = "batchFactory")
                public void handleBatch(List<ConsumerRecord<String, NotificationEvent>> records) {
                    for (ConsumerRecord<String, NotificationEvent> record : records) {
                        tracing.run(() -> processRecord(record));
                    }
                }
                private void processRecord(ConsumerRecord<String, NotificationEvent> record) {
                    service.process(record.value());
                }
            }
            """);
        write(dir, "notification/src/main/java/shop/notification/Tracing.java", """
            package shop.notification;
            @Component
            class Tracing {
                void run(Runnable work) {
                    work.run();
                }
            }
            """);
        write(dir, "notification/src/main/java/shop/notification/NotificationService.java", """
            package shop.notification;
            @Service
            class NotificationService {
                private JdbcTemplate jdbc;
                void process(NotificationEvent event) {
                    jdbc.update("insert into notification (event_id) values (?)", event.eventId());
                }
            }
            """);
        write(dir, "notification/src/main/java/shop/notification/NotificationEvent.java", """
            package shop.notification;
            public record NotificationEvent(String eventId, String userId) {}
            """);
        write(dir, "notification/src/main/java/shop/notification/AuditListener.java", """
            package shop.notification;
            @Component
            @KafkaListener(topics = "ecommerce-audit-topic", groupId = "audit")
            class AuditListener {
                @KafkaHandler
                void onAudit(NotificationEvent event) {}
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
