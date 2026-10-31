package dev.faultline.demo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.faultline.demo.TestcontainersConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two workers credit the shop at the same time: the first has read the balance when the second credits it. That is
 * the moment Faultline's barrier holds a worker at; here the test holds it with a sleep.
 */
@Import(TestcontainersConfiguration.class)
// no payment provider in tests: payments are not charged
@SpringBootTest(properties = "payments.provider.url=")
class MerchantBalanceTest {

	@Autowired
	JdbcClient db;

	@Autowired
	TransactionTemplate tx;

	@ParameterizedTest
	@CsvSource({"read-then-write, 100", "locked, 150"})
	void aSecondCreditWhileTheFirstHasReadIsKeptOnlyWhenLocked(String mode, long expectedGain) throws Exception {
		var merchant = new MerchantBalance(db, mode);
		long before = balance();
		var otherWorker = new AtomicReference<CompletableFuture<Void>>();

		tx.executeWithoutResult(s -> {
			long read = merchant.read();
			otherWorker.set(CompletableFuture.runAsync(() -> tx.executeWithoutResult(t -> merchant.credit(50))));
			sleep(300);   // read-then-write: the other worker commits meanwhile. locked: it waits for this one
			assertThat(otherWorker.get().isDone()).isEqualTo(mode.equals("read-then-write"));
			merchant.write(read + 100);
		});
		otherWorker.get().get(5, TimeUnit.SECONDS);

		assertThat(balance() - before).isEqualTo(expectedGain);
	}

	private long balance() {
		return db.sql("select balance_minor from merchant where merchant_id = 'shop'").query(Long.class).single();
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
