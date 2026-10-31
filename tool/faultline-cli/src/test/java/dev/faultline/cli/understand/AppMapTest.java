package dev.faultline.cli.understand;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import dev.faultline.cli.Json;
import dev.faultline.cli.source.Endpoint;
import org.junit.jupiter.api.Test;

class AppMapTest {
    private static final List<Endpoint> ENDPOINTS = List.of(
        Endpoint.http("POST", "/payments", "PaymentController#pay", "a.java", 1),
        Endpoint.kafka(List.of("payments.requested"), "worker", "PaymentListener#onMessage", "b.java", 1));
    private static final Set<String> HANDLERS = Set.of("PaymentController#pay", "PaymentListener#onMessage", "PaymentService#process");

    @Test
    void aMapTiedToRealCodeHasNoProblems() {
        assertThat(map("PaymentController#pay", "PaymentService#process").problems(ENDPOINTS, HANDLERS)).isEmpty();
    }

    @Test
    void inventedHandlersAndMissingEntryPointsAreProblems() {
        var problems = map("PaymentController#create", "PaymentService#charge").problems(ENDPOINTS, HANDLERS);

        assertThat(problems).anyMatch(p -> p.contains("'PaymentController#create', which is not an endpoint"));
        assertThat(problems).anyMatch(p -> p.equals("no entry point for PaymentController#pay (POST /payments)"));
        assertThat(problems).anyMatch(p -> p.contains("step in 'PaymentService#charge', which is not a method"));
    }

    @Test
    void handlersWithAPackageMeanTheSameMethod() {
        String json = """
            {"summary":"Payments.","modules":[],"dataStores":[],"outsideCalls":[],
             "entryPoints":[{"kind":"http","name":"POST /payments","handler":"dev.demo.PaymentController#pay","purpose":"pay"},
                            {"kind":"kafka","name":"kafka payments.requested","handler":"dev.demo.PaymentListener#onMessage","purpose":"apply"}],
             "journeys":[{"name":"make-payment","description":"pay","steps":[{"handler":"dev.demo.PaymentService#process","does":"debit"}]}]}
            """;
        var map = Json.MAPPER.readValue(json, AppMap.class);

        assertThat(map.entryPoints()).extracting(AppMap.EntryPoint::handler).containsExactly("PaymentController#pay", "PaymentListener#onMessage");
        assertThat(map.problems(ENDPOINTS, HANDLERS)).isEmpty();
    }

    @Test
    void journeyNamesAreKebabCase() {
        var map = new AppMap(null, null, "s", List.of(), entryPoints(), List.of(), List.of(),
            List.of(new AppMap.Journey("Make Payment", "d", List.of(new AppMap.Step("PaymentController#pay", "x")))));
        assertThat(map.problems(ENDPOINTS, HANDLERS)).containsExactly("journey name 'Make Payment' is not kebab-case");
    }

    private static AppMap map(String entryHandler, String stepHandler) {
        var entries = List.of(new AppMap.EntryPoint("http", "POST /payments", entryHandler, "pay"),
            new AppMap.EntryPoint("kafka", "kafka payments.requested", "PaymentListener#onMessage", "apply"));
        var journey = new AppMap.Journey("make-payment", "pay", List.of(new AppMap.Step(stepHandler, "debit")));
        return new AppMap(null, null, "Payments.", List.of(), entries, List.of(), List.of(), List.of(journey));
    }

    private static List<AppMap.EntryPoint> entryPoints() {
        return List.of(new AppMap.EntryPoint("http", "POST /payments", "PaymentController#pay", "pay"),
            new AppMap.EntryPoint("kafka", "kafka payments.requested", "PaymentListener#onMessage", "apply"));
    }
}
