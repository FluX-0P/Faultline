package dev.faultline.cli.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.faultline.cli.Json;

/** An app shaped like ewolff's order service: an HTML form bound to a JPA entity, with Formatters for its references. */
class FormsAndEntitiesTest {
    @TempDir
    Path dir;

    private Path orders() throws IOException {
        write("pom.xml", "<project><artifactId>orders</artifactId><version>1.0.0</version></project>\n");
        write("src/main/java/shop/OrdersApp.java", "package shop;\n@SpringBootApplication\npublic class OrdersApp {}\n");
        write("src/main/java/shop/OrderController.java", """
            package shop;
            @Controller
            class OrderController {
                private OrderService service;
                @PostMapping("/")
                public ModelAndView post(Order order) {
                    service.place(order);
                    return new ModelAndView("success");
                }
                @PostMapping("/api/orders")
                public ResponseEntity<OrderAnswer> api(@RequestBody Order order) {
                    return ResponseEntity.ok(new OrderAnswer(service.place(order).getId(), order.getShippingAddress()));
                }
            }
            """);
        write("src/main/java/shop/OrderService.java", """
            package shop;
            @Service
            class OrderService {
                private OrderRepository orders;
                Order place(Order order) {
                    return orders.save(order);
                }
            }
            """);
        write("src/main/java/shop/OrderRepository.java", "package shop;\npublic interface OrderRepository extends CrudRepository<Order, Long> {}\n");
        write("src/main/java/shop/Order.java", """
            package shop;
            @Entity
            @Table(name = "ORDERTABLE")
            public class Order {
                @Id
                @GeneratedValue
                private long id;
                @ManyToOne
                private Customer customer;
                private Date updated;
                @Embedded
                private Address shippingAddress;
                @OneToMany(cascade = CascadeType.ALL)
                private List<OrderLine> orderLine;
            }
            """);
        write("src/main/java/shop/OrderLine.java", """
            package shop;
            @Entity
            public class OrderLine {
                private int count;
                @ManyToOne
                private Item item;
                @Id
                @GeneratedValue
                private long id;
            }
            """);
        write("src/main/java/shop/Customer.java", "package shop;\n@Entity\npublic class Customer {\n    @Id\n    private long customerId;\n    private String name;\n}\n");
        write("src/main/java/shop/Item.java", "package shop;\n@Entity\npublic class Item {\n    @Id\n    private long itemId;\n}\n");
        write("src/main/java/shop/Address.java", "package shop;\n@Embeddable\npublic class Address {\n    private String street;\n    private String zip;\n}\n");
        write("src/main/java/shop/OrderAnswer.java", "package shop;\npublic record OrderAnswer(long orderId, Address address) {}\n");
        write("src/main/java/shop/CustomerFormatter.java", "package shop;\n@Component\npublic class CustomerFormatter implements Formatter<Customer> {}\n");
        write("src/main/java/shop/ItemFormatter.java", "package shop;\n@Component\npublic class ItemFormatter implements Formatter<Item> {}\n");
        return dir;
    }

    @Test
    void aHandlerParameterWithoutRequestBodyIsAnHtmlForm() throws Exception {
        var shape = RequestShapes.of(App.read(orders())).get("POST /");

        assertThat(shape.form()).isTrue();
        assertThat(shape.source()).isEqualTo("the form fields of Order");
        // no generated ID, no Date (a form field Faultline cannot write), references as the text a Formatter reads
        assertThat(shape.describe()).isEqualTo("""
            - customer: string (one text value that CustomerFormatter reads)
            - shippingAddress: object
              - street: string
              - zip: string
            - orderLine: array, of object
              - count: integer, required
              - item: string (one text value that ItemFormatter reads)""");
    }

    @Test
    void aFormsFieldsMayBeWrittenNestedOrFlat() throws Exception {
        var shape = RequestShapes.of(App.read(orders())).get("POST /");

        var nested = Json.MAPPER.readTree("{\"customer\": \"1\", \"shippingAddress\": {\"street\": \"x\"}, \"orderLine\": [{\"count\": 1, \"item\": \"2\"}]}");
        var flat = Json.MAPPER.readTree("{\"customer\": \"1\", \"shippingAddress.street\": \"x\", \"orderLine[0].count\": 1, \"orderLine[0].item\": \"2\"}");
        assertThat(shape.problems(nested)).isEmpty();
        assertThat(shape.problems(flat)).isEmpty();
        assertThat(shape.problems(Json.MAPPER.readTree("{\"orderLine[0].count\": \"one\"}")))
            .containsExactly("field orderLine[0].count must be an integer, not \"one\"");
    }

    @Test
    void theAnswersFieldsComeFromTheTypeTheHandlerReturns() throws Exception {
        var answers = RequestShapes.answers(App.read(orders()));

        assertThat(answers.get("POST /api/orders")).containsExactly("orderId", "address", "address.street", "address.zip");
        assertThat(answers).doesNotContainKey("POST /");
    }

    @Test
    void savingAnEntityWritesItsTableItsCascadesAndItsJoinTables() throws Exception {
        var code = App.read(orders()).code();

        assertThat(JpaTables.saved(code, code.method("OrderService#place").orElseThrow()))
            .containsExactly("order_line", "ordertable", "ordertable_order_line");
        assertThat(JpaTables.snake("ShipmentLine")).isEqualTo("shipment_line");
    }

    private void write(String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
