package dev.faultline.cli.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModulesTest {
    @TempDir
    Path dir;

    @Test
    void aRepoIsItsSpringBootApps() throws Exception {
        var app = App.read(TestRepos.shop(dir));

        assertThat(app.modules()).extracting(App.Module::path).containsExactly("invoicing", "order", "shipping");
        assertThat(app.modules()).extracting(App.Module::name).containsExactly("invoicing", "order", "shipping");
        assertThat(app.multiModule()).isTrue();
        assertThatThrownBy(app::config).hasMessageContaining("has 3 Spring Boot apps");
    }

    @Test
    void aClassNameTwoModulesShareCarriesItsModule() throws Exception {
        var code = App.read(TestRepos.shop(dir)).code();

        assertThat(code.handlers()).contains("OrderController#place", "shipping:OrderListener#onOrder", "invoicing:OrderListener#onOrder",
            "ShipmentService#ship", "InvoiceService#bill");
        assertThat(code.method("shipping:OrderListener#onOrder").orElseThrow().module()).isEqualTo("shipping");
        assertThat(code.method("Money#cents")).isEmpty();
        assertThat(code.outline()).contains("class shipping:OrderListener", "class invoicing:OrderListener", "class OrderController");
    }

    @Test
    void callsStayInTheirModule() throws Exception {
        var code = App.read(TestRepos.shop(dir)).code();

        var shipping = code.method("shipping:OrderListener#onOrder").orElseThrow();
        var invoicing = code.method("invoicing:OrderListener#onOrder").orElseThrow();
        assertThat(code.calledMethods(shipping)).extracting(JavaSources.Method::handler).containsExactly("ShipmentService#ship");
        assertThat(code.calledMethods(invoicing)).extracting(JavaSources.Method::handler).containsExactly("InvoiceService#bill");
    }

    @Test
    void eachModuleIsJudgedByItsOwnConfiguration() throws Exception {
        var app = App.read(TestRepos.shop(dir));
        var listener = app.code().method("shipping:OrderListener#onOrder").orElseThrow();

        assertThat(app.configOf(listener).get("spring.datasource.url")).contains("jdbc:postgresql://shipping-db/shipping");
        assertThat(app.module("order").orElseThrow().config().get("server.port")).contains("8080");
        assertThat(app.code().endpoints()).filteredOn(e -> e.kind() == Endpoint.Kind.KAFKA)
            .extracting(Endpoint::handler, Endpoint::topics)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("shipping:OrderListener#onOrder", java.util.List.of("orders")),
                org.assertj.core.groups.Tuple.tuple("invoicing:OrderListener#onOrder", java.util.List.of("orders")));
    }

    @Test
    void settingsApplyToEveryModule() throws Exception {
        var app = App.read(TestRepos.shop(dir)).withSettings(Map.of("shop.topic", "orders.faultline"));

        assertThat(app.code().sentTopics(app.code().method("OrderService#place").orElseThrow())).containsExactly("orders.faultline");
        assertThat(app.code().endpoints()).filteredOn(e -> e.kind() == Endpoint.Kind.KAFKA)
            .allSatisfy(e -> assertThat(e.topics()).containsExactly("orders.faultline"));
    }

    @Test
    void aFolderWithoutSpringBootApplicationIsOneApp() throws Exception {
        var app = App.read(TestApps.payments(dir));

        assertThat(app.modules()).extracting(App.Module::path).containsExactly(App.ROOT_MODULE);
        assertThat(app.config().get("server.port")).contains("8081");
    }

    @Test
    void theDemoAppIsOneModuleAtItsRoot() throws Exception {
        var app = App.read(Path.of("../../demo-app"));

        assertThat(app.modules()).extracting(App.Module::path).containsExactly(App.ROOT_MODULE);
        assertThat(app.modules().getFirst().name()).isEqualTo("demo-app");
    }
}
