package dev.faultline.cli.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatsTest {
    @Test
    void anOperationOnlyTheSpecHasIsAnEndpointHandledByTheMethodThatImplementsTheGeneratedInterface(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));

        var http = app.code().endpoints().stream().filter(e -> e.kind() == Endpoint.Kind.HTTP).toList();

        assertThat(http).extracting(Endpoint::name, Endpoint::handler).containsExactly(
            tuple("POST /cart/{userId}/items", "CartController#cartUserIdItemsPost"),
            tuple("POST /cart/{userId}/checkout", "CartController#cartUserIdCheckoutPost"),
            // no class implements it: it is still a way in, named after its operation
            tuple("PUT /cart/{userId}/coupons", "openapi:applyCoupon"));
        assertThat(http.getFirst().spec()).isEqualTo("cart-api/src/main/resources/service-api.yaml");
        assertThat(http.getFirst().file()).isEqualTo("cart/src/main/java/shop/cart/CartController.java");
        assertThat(app.moduleOf(app.code().method("CartController#cartUserIdItemsPost").orElseThrow())).map(App.Module::name).hasValue("cart");
    }

    @Test
    void theGeneratorNamesAnOperationWithoutAnIdAfterItsPathAndMethod() {
        assertThat(OpenApi.javaName("", "/cart/{userId}/items", "post")).isEqualTo("cartUserIdItemsPost");
        assertThat(OpenApi.javaName(null, "/orders/{order-id}/history", "get")).isEqualTo("ordersOrderIdHistoryGet");
        assertThat(OpenApi.javaName("", "/", "get")).isEqualTo("rootGet");
        assertThat(OpenApi.javaName("get_orders", "/orders", "get")).isEqualTo("getOrders");
        assertThat(OpenApi.javaName("GetOrders", "/orders", "get")).isEqualTo("getOrders");
        assertThat(OpenApi.javaName("new", "/orders", "post")).isEqualTo("callNew");
    }

    @Test
    void anAnnotatedMappingWinsOverTheSpecsOperationForTheSamePath(@TempDir Path dir) throws Exception {
        var repo = TestRepos.ecommerce(dir);
        Files.writeString(repo.resolve("cart/src/main/java/shop/cart/CouponController.java"), """
            package shop.cart;
            @RestController
            class CouponController {
                @PutMapping("/cart/{id}/coupons")
                void apply(@PathVariable String id) {}
            }
            """);

        var coupons = App.read(repo).code().endpoints().stream().filter(e -> e.path() != null && e.path().endsWith("/coupons")).toList();

        assertThat(coupons).singleElement().satisfies(e -> {
            assertThat(e.handler()).isEqualTo("CouponController#apply");
            assertThat(e.spec()).isNull();
        });
    }

    @Test
    void aFeignClientsMappingIsACallToAnotherServiceNotAWayIn(@TempDir Path dir) throws Exception {
        var repo = TestRepos.ecommerce(dir);
        Files.writeString(repo.resolve("order/src/main/java/shop/order/CartClient.java"), """
            package shop.order;
            @FeignClient(name = "cart", url = "${cart.url}")
            public interface CartClient {
                @RequestMapping(method = RequestMethod.POST, value = "/cart/{userId}/checkout")
                ResponseEntity<Void> cartUserIdCheckoutPost(@PathVariable UUID userId);
            }
            """);

        var checkout = App.read(repo).code().endpoints().stream().filter(e -> "POST /cart/{userId}/checkout".equals(e.method() + " " + e.path())).toList();

        assertThat(checkout).singleElement().satisfies(e -> assertThat(e.handler()).isEqualTo("CartController#cartUserIdCheckoutPost"));
    }

    @Test
    void aGeneratedEndpointsBodyAndAnswerComeFromTheSpec(@TempDir Path dir) throws Exception {
        var app = App.read(TestRepos.ecommerce(dir));

        var shape = RequestShapes.of(app).get("POST /cart/{userId}/items");

        assertThat(shape.fieldNames()).isEqualTo("productId, quantity");
        assertThat(RequestShapes.answers(app).get("POST /cart/{userId}/items")).containsExactly("cartId", "owner", "owner.userId");
    }

    @Test
    void aBatchListenersMessageIsTheTypeInsideItsRecords(@TempDir Path dir) throws Exception {
        var code = App.read(TestRepos.ecommerce(dir)).code();
        var batch = code.method("NotificationHandler#handleBatch").orElseThrow();
        var receive = code.method("OrderEventListener#receive").orElseThrow();

        assertThat(code.isBatchListener(batch)).isTrue();
        assertThat(code.isBatchListener(receive)).isFalse();
        assertThat(code.messageTypes(batch)).extracting(t -> t.getNameAsString()).containsExactly("NotificationEvent");
        assertThat(code.messageTypes(receive)).extracting(t -> t.getNameAsString()).containsExactly("OrderCreatedEvent");
        // its work is in the method for one record, called from a lambda
        assertThat(code.workOf(batch)).extracting(JavaSources.Method::handler).containsExactly("Tracing#run", "NotificationService#process");
    }

    @Test
    void aSendToReplyIsASendToItsTopic(@TempDir Path dir) throws Exception {
        var code = App.read(TestRepos.ecommerce(dir)).code();
        var receive = code.method("OrderEventListener#receive").orElseThrow();

        assertThat(code.replies(receive)).isTrue();
        assertThat(code.replyTopics(receive)).containsExactly("ecommerce-payments-in-topic");
        assertThat(code.sentTopics(receive)).containsExactly("ecommerce-payments-in-topic");
        assertThat(code.sentTopics(code.method("OrderEventSender#send").orElseThrow())).containsExactly("ecommerce-orders-topic");
    }

    @Test
    void aClassLevelListenersHandlersAreListenersOfItsTopics(@TempDir Path dir) throws Exception {
        var code = App.read(TestRepos.ecommerce(dir)).code();

        assertThat(code.kafkaListeners()).extracting(JavaSources.Method::handler)
            .containsExactlyInAnyOrder("OrderEventListener#receive", "NotificationHandler#handleBatch", "AuditListener#onAudit");
        assertThat(code.endpoints().stream().filter(e -> e.handler().equals("AuditListener#onAudit")).toList()).singleElement()
            .satisfies(e -> {
                assertThat(e.topics()).isEqualTo(List.of("ecommerce-audit-topic"));
                assertThat(e.groupId()).isEqualTo("audit");
            });
    }
}
