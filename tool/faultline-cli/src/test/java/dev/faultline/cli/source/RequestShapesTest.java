package dev.faultline.cli.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.faultline.cli.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestShapesTest {

    @Test
    void theDemoAppsBodyComesFromItsRequestRecord() throws Exception {
        var shape = RequestShapes.of(App.read(Path.of("../../demo-app"))).get("POST /payments");

        assertThat(shape.source()).isEqualTo("PaymentRequested");
        assertThat(shape.describe()).isEqualTo("""
            - paymentId: string
            - accountId: string
            - amountMinor: integer, required""");
    }

    @Test
    void validationAnnotationsEnumsNestedTypesAndListsShapeTheBody(@TempDir Path dir) throws Exception {
        write(dir, "src/main/java/shop/OrderController.java", """
            package shop;
            @RestController
            class OrderController {
                @PostMapping("/orders")
                void place(@RequestBody @Valid PlaceOrder order) {}
            }
            """);
        write(dir, "src/main/java/shop/PlaceOrder.java", """
            package shop;
            record PlaceOrder(@NotBlank @JsonProperty("order_id") String orderId, @Positive int quantity,
                              @Min(100) @Max(value = 50000) Long priceCents, Channel channel, Address shipTo,
                              List<Line> lines, UUID customer, boolean gift) {
                static final int LIMIT = 9;
            }
            """);
        write(dir, "src/main/java/shop/Channel.java", "package shop; enum Channel { WEB, STORE }");
        write(dir, "src/main/java/shop/Address.java", "package shop; class Address { @NotNull private String city; private static int COUNT; }");
        write(dir, "src/main/java/shop/Line.java", "package shop; record Line(String sku, double weight) {}");

        var shape = RequestShapes.of(App.read(dir)).get("POST /orders");

        assertThat(shape.describe()).isEqualTo("""
            - order_id: string, required
            - quantity: integer, required, at least 1
            - priceCents: integer, at least 100, at most 50000
            - channel: string, one of WEB, STORE
            - shipTo: object
              - city: string, required
            - lines: array, of object
            - customer: string (uuid)
            - gift: boolean, required""");
        assertThat(shape.fields().get(5).items().fields()).extracting(RequestShape.Field::name).containsExactly("sku", "weight");
    }

    @Test
    void anOpenApiSpecDescribesTheBodyWhenTheRepoHasOne(@TempDir Path dir) throws Exception {
        write(dir, "src/main/java/shop/OrderController.java", """
            package shop;
            @RestController
            class OrderController {
                @PostMapping("/orders")
                void place(@RequestBody PlaceOrder order) {}
            }
            """);
        write(dir, "src/main/java/shop/PlaceOrder.java", "package shop; record PlaceOrder(String orderId) {}");
        write(dir, "api/openapi.yaml", """
            openapi: 3.0.3
            info: {title: Shop, version: "1"}
            paths:
              /orders:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema: {$ref: '#/components/schemas/PlaceOrder'}
            components:
              schemas:
                Base:
                  type: object
                  required: [orderId]
                  properties:
                    orderId: {type: string, format: uuid}
                PlaceOrder:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      required: [quantity]
                      properties:
                        quantity: {type: integer, minimum: 1, maximum: 10}
                        channel: {type: string, enum: [WEB, STORE]}
                        note: {oneOf: [{type: string}, {type: integer}]}
            """);

        var shape = RequestShapes.of(App.read(dir)).get("POST /orders");

        assertThat(shape.source()).isEqualTo("api/openapi.yaml");
        assertThat(shape.describe()).isEqualTo("""
            - orderId: string (uuid), required
            - quantity: integer, required, at least 1, at most 10
            - channel: string, one of WEB, STORE
            - note: any value""");
    }

    @Test
    void aSwagger2SpecInJsonWorksToo(@TempDir Path dir) throws Exception {
        write(dir, "src/main/resources/static/swagger.json", """
            {"swagger": "2.0", "paths": {"/orders": {"put": {"parameters": [
              {"in": "body", "name": "order", "schema": {"$ref": "#/definitions/Order"}}]}}},
             "definitions": {"Order": {"type": "object", "properties": {"id": {"type": "string"}}}}}
            """);
        write(dir, "src/main/resources/application.yml", "server:\n  port: 8080\n");

        var shapes = RequestShapes.of(App.read(dir));

        assertThat(shapes.get("PUT /orders").describe()).isEqualTo("- id: string");
        assertThat(App.read(dir).files().openApiSpecs()).extracting(RepoFiles.TextFile::path).containsExactly("src/main/resources/static/swagger.json");
    }

    @Test
    void saysWhatIsWrongWithABody() throws Exception {
        var shape = new RequestShape("PlaceOrder", List.of(
            new RequestShape.Field("orderId", "string", true, List.of(), null, null, null, List.of(), null),
            new RequestShape.Field("quantity", "integer", false, List.of(), 1.0, 10.0, null, List.of(), null),
            new RequestShape.Field("channel", "string", false, List.of("WEB", "STORE"), null, null, null, List.of(), null)));

        assertThat(shape.problems(Json.MAPPER.readTree("{\"orderId\": \"o-1\", \"quantity\": 3, \"channel\": \"WEB\"}"))).isEmpty();
        assertThat(shape.problems(Json.MAPPER.readTree("{\"quantity\": \"3\", \"channel\": \"PHONE\", \"price\": 5}"))).containsExactly(
            "field price is not in the request body (its fields: orderId, quantity, channel)",
            "field orderId is required",
            "field quantity must be an integer, not \"3\"",
            "field channel must be one of WEB, STORE, not \"PHONE\"");
        assertThat(shape.problems(Json.MAPPER.readTree("{\"orderId\": \"o-1\", \"quantity\": 0}")))
            .containsExactly("field quantity must be at least 1, not 0");
    }

    private static void write(Path dir, String path, String text) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
