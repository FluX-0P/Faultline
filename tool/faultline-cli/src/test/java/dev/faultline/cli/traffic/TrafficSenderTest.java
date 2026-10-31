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
            seen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try (var sender = new TrafficSender(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Map.of("Authorization", "Bearer t0ken"))) {
            var entry = sender.sendOne(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/payments"), "POST",
                new Request(0, "p-1", "{\"paymentId\": \"p-1\"}"));

            assertThat(entry.status()).isEqualTo(HistoryEntry.Status.OK);
            assertThat(seen).isEqualTo(List.of("Bearer t0ken"));
        } finally {
            server.stop(0);
        }
    }
}
