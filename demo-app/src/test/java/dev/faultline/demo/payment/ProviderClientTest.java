package dev.faultline.demo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntUnaryOperator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The charge call against a small local provider that answers as each test tells it to. */
class ProviderClientTest {
	private static final PaymentEvent PAYMENT = new PaymentEvent("event-1", "pay-1", "acct-1", 2_500);

	private final List<String> keys = new CopyOnWriteArrayList<>();
	private HttpServer server;

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void aRefusedChargeIsTriedUntilItGoesThroughWithThePaymentIdAsItsKey() throws Exception {
		start(n -> n <= 2 ? 503 : 201);

		client("payment-id", "with-backoff").charge(PAYMENT);

		assertThat(keys).containsExactly("pay-1", "pay-1", "pay-1");
	}

	@Test
	void withoutAKeyAChargeThatTimedOutIsSentAgainAsANewOne() throws Exception {
		start(n -> {
			if (n == 1) sleep(1_000);   // charged, but the reply comes after the client gave up
			return 201;
		});

		client("none", "with-backoff").charge(PAYMENT);

		assertThat(keys).containsExactly("(none)", "(none)");
	}

	@Test
	void immediatelyRetriesWithoutWaitingAndWithBackoffWaitsLongerEachTime() throws Exception {
		start(n -> n % 4 == 0 ? 201 : 503);
		long start = System.nanoTime();
		client("none", "immediately").charge(PAYMENT);
		long immediately = (System.nanoTime() - start) / 1_000_000;

		start = System.nanoTime();
		client("none", "with-backoff").charge(PAYMENT);
		long withBackoff = (System.nanoTime() - start) / 1_000_000;

		assertThat(keys).hasSize(8);
		assertThat(withBackoff).isGreaterThanOrEqualTo(350);   // three waits of at least 50, 100 and 200ms
		assertThat(immediately).isLessThan(withBackoff);
	}

	private ProviderClient client(String key, String retry) {
		return new ProviderClient("http://localhost:" + server.getAddress().getPort(), Duration.ofMillis(300), key, retry, JsonMapper.builder().build());
	}

	/** {@code status} gives the status for the nth request, counted from 1. */
	private void start(IntUnaryOperator status) throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/charges", ex -> {
			String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
			keys.add(key == null ? "(none)" : key);
			ex.getRequestBody().readAllBytes();
			int code = status.applyAsInt(keys.size());
			try {
				ex.sendResponseHeaders(code, -1);
			} catch (IOException e) {
				// the client had already given up
			}
			ex.close();
		});
		server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		server.start();
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
