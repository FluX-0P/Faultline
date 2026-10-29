package dev.faultline.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentFlowTest {

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcClient db;

	@Test
	void paymentIsAppliedExactlyOnce() throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""
				{"paymentId":"test-pay-1","accountId":"acct-1","amountMinor":2500}"""))
			.build();
		try (var http = HttpClient.newHttpClient()) {
			assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(202);
		}

		// the listener applies the payment asynchronously, so wait for it
		long deadline = System.currentTimeMillis() + 30_000;
		while (paymentRows("test-pay-1") == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(200);
		}

		assertThat(paymentRows("test-pay-1")).isEqualTo(1);
		assertThat(db.sql("select balance_minor from accounts where account_id = 'acct-1'")
			.query(Long.class).single()).isEqualTo(997_500L);
	}

	private long paymentRows(String paymentId) {
		return db.sql("select count(*) from payments where payment_id = ?")
			.param(paymentId).query(Long.class).single();
	}
}
