package dev.faultline.cli.stub;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.faultline.cli.Json;
import tools.jackson.core.JacksonException;

/**
 * A fake payment provider, Faultline's stand-in for a real one. {@code POST /charges} charges a payment into the
 * {@link Ledger} and honours an {@code Idempotency-Key} header. Under {@code /control/}, faultline run tells it to break:
 * answer every nth charge with an error, slowly, or only after charging and then too late for the caller
 * ({@link ArmedFault}), or go through an outage ({@link Availability}). Each request runs on its own virtual thread,
 * so a late reply holds up nobody else.
 */
public final class FakeProvider implements AutoCloseable {
    record ChargeRequest(String paymentId, String accountId, Long amountMinor) {}

    record FaultRequest(String action, Long nthHit, Integer times, Long delayMs) {}

    record OutageRequest(Boolean down) {}

    private final Ledger ledger;
    private final Availability availability;
    private final ArmedFault fault = new ArmedFault();
    private final Consumer<String> log;
    private final HttpServer server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    private FakeProvider(int port, Ledger ledger, int recoverBelow, Consumer<String> log) throws IOException {
        this.ledger = ledger;
        this.availability = new Availability(recoverBelow, System::nanoTime);
        this.log = log;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(threads);
        server.createContext("/charges", this::charges);
        server.createContext("/health", ex -> handle(ex, () -> reply(ex, 200, Map.of("state", availability.state()))));
        server.createContext("/control/", this::control);
    }

    public static FakeProvider start(int port, String dbUrl, String dbUser, String dbPassword, int recoverBelow, Consumer<String> log)
            throws IOException, SQLException {
        var provider = new FakeProvider(port, new Ledger(dbUrl, dbUser, dbPassword, 8), recoverBelow, log);
        provider.server.start();
        return provider;
    }

    private void charges(HttpExchange ex) {
        handle(ex, () -> {
            if (!ex.getRequestMethod().equals("POST")) {
                reply(ex, 405, Map.of("error", "POST a charge"));
                return;
            }
            if (!availability.admit()) {
                reply(ex, 503, Map.of("error", "unavailable"));
                return;
            }
            ChargeRequest charge;
            try {
                charge = Json.MAPPER.readValue(ex.getRequestBody(), ChargeRequest.class);
            } catch (JacksonException e) {
                reply(ex, 400, Map.of("error", "not a charge: " + e.getOriginalMessage()));
                return;
            }
            if (charge.paymentId() == null || charge.amountMinor() == null) {
                reply(ex, 400, Map.of("error", "a charge needs paymentId and amountMinor"));
                return;
            }
            String injected = fault.onCharge(charge.paymentId());
            if ("error".equals(injected)) {
                reply(ex, 503, Map.of("error", "injected by faultline"));
                return;
            }
            if ("slow".equals(injected)) Thread.sleep(fault.delayMs());
            var result = ledger.charge(charge.paymentId(), charge.accountId(), charge.amountMinor(),
                ex.getRequestHeaders().getFirst("Idempotency-Key"));
            // charged already; the reply arrives after the caller has given up waiting for it
            if ("late-reply".equals(injected)) Thread.sleep(fault.delayMs());
            reply(ex, result.replayed() ? 200 : 201, Map.of("chargeId", result.chargeId(), "replayed", result.replayed()));
        });
    }

    private void control(HttpExchange ex) {
        handle(ex, () -> {
            String what = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            switch (what) {
                case "POST /control/reset" -> {
                    fault.disarm();
                    availability.reset();
                    reply(ex, 200, Map.of("state", availability.state()));
                }
                case "POST /control/fault" -> {
                    var f = Json.MAPPER.readValue(ex.getRequestBody(), FaultRequest.class);
                    fault.arm(f.action(), f.nthHit() == null ? 1 : f.nthHit(), f.times() == null ? 1 : f.times(), f.delayMs() == null ? 0 : f.delayMs());
                    log.accept("stub      will %s every %dth charge, %d times".formatted(f.action(), f.nthHit(), f.times()));
                    reply(ex, 200, Map.of("armed", f.action()));
                }
                case "POST /control/outage" -> {
                    var o = Json.MAPPER.readValue(ex.getRequestBody(), OutageRequest.class);
                    if (Boolean.TRUE.equals(o.down())) availability.startOutage();
                    else availability.endOutage();
                    log.accept("stub      " + availability.state());
                    reply(ex, 200, Map.of("state", availability.state()));
                }
                case "GET /control/fired" -> reply(ex, 200, fault.fired());
                default -> reply(ex, 404, Map.of("error", "no " + what));
            }
        });
    }

    private interface Handler {
        void run() throws Exception;
    }

    private void handle(HttpExchange ex, Handler handler) {
        try (ex) {
            handler.run();
        } catch (IllegalArgumentException | JacksonException e) {
            replyQuietly(ex, 400, e.getMessage());
        } catch (IOException e) {
            // the caller hung up, such as after its timeout: nobody is left to answer
        } catch (Exception e) {
            replyQuietly(ex, 500, e.toString());
        }
    }

    private static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void replyQuietly(HttpExchange ex, int status, String error) {
        try {
            reply(ex, status, Map.of("error", String.valueOf(error)));
        } catch (IOException e) {
            // nobody to tell
        }
    }

    @Override
    public void close() {
        server.stop(0);
        threads.close();
        ledger.close();
    }
}
