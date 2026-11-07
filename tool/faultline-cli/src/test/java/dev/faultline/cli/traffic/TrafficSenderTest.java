package dev.faultline.cli.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import dev.faultline.cli.history.HistoryEntry;
import dev.faultline.cli.workload.Request;
import org.junit.jupiter.api.Test;

class TrafficSenderTest {

    @Test
    void everyRequestCarriesTheAuthHeader() throws Exception {
        var seen = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try (var sender = new TrafficSender(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Map.of("Authorization", "Bearer t0ken"))) {
            var entry = sender.sendOne(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/payments"), "POST",
                new Request(0, "p-1", "{\"paymentId\": \"p-1\"}"));
            // a header of each request's own takes its ID
            sender.sendRequestHeaders(Map.of("Idempotency-Key", "key-${id}"));
            sender.sendOne(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/payments"), "POST",
                new Request(1, "p-2", "{\"paymentId\": \"p-2\"}"));

            assertThat(entry.status()).isEqualTo(HistoryEntry.Status.OK);
            assertThat(seen).isEqualTo(List.of("Bearer t0ken null", "Bearer t0ken key-p-2"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFormGetsItsJsonAsFieldsAndTheAnswerGivesTheIdTheAppMade() throws Exception {
        var seen = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst("Content-Type") + " " + new String(exchange.getRequestBody().readAllBytes()));
            byte[] answer = "{\"order\": {\"id\": 42}}".getBytes();
            exchange.sendResponseHeaders(201, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var sender = new TrafficSender(URI.create(base), Map.of(), TrafficSender.FORM, "order.id")) {
            var entry = sender.sendOne(URI.create(base + "/orders"), "POST",
                new Request(0, "Main St 1", "{\"customer\": \"1\", \"shippingAddress\": {\"street\": \"Main St 1\"}, \"orderLine\": [{\"count\": 2}]}"));

            assertThat(seen).containsExactly(TrafficSender.FORM + " customer=1&shippingAddress.street=Main+St+1&orderLine%5B0%5D.count=2");
            assertThat(entry.effect()).isEqualTo("42");
        } finally {
            server.stop(0);
        }
    }
}
