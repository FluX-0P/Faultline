package dev.faultline.demo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.faultline.demo.TestcontainersConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One redelivery, processed one after the other. Only "none" (B1) applies it twice here; check-then-insert (B10)
 * fails only when two workers race, which takes Faultline's frozen-worker fault to show.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DedupeTest {

	@Autowired
	JdbcClient db;

	@ParameterizedTest
	@CsvSource({"none, 2", "check-then-insert, 1", "claim-first, 1"})
	void aRedeliveredPaymentIsAppliedAsOftenAsTheModeAllows(String mode, long expectedRows) {
		var service = new PaymentService(db, mode);
		var payment = new PaymentRequested("dedupe-" + mode, "acct-2", 1_000);

		service.process(payment);
		service.process(payment);   // what a redelivery after a crash looks like

		assertThat(db.sql("select count(*) from payments where payment_id = ?").param(payment.paymentId()).query(Long.class).single())
			.isEqualTo(expectedRows);
	}
}
