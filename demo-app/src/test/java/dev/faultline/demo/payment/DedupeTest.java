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
 * Payments processed one after the other. A redelivery keeps its event ID; a client's second submit of the same payment
 * gets a new one. check-then-insert (B10) fails only when two workers race, which takes Faultline's frozen-worker fault.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DedupeTest {

	@Autowired
	JdbcClient db;

	@ParameterizedTest
	@CsvSource({"none, 2", "check-then-insert, 1", "claim-first, 1"})
	void aRedeliveredPaymentIsAppliedAsOftenAsTheModeAllows(String mode, long expectedRows) {
		var service = new PaymentService(db, mode, "payment-id");
		var event = new PaymentEvent("event-1", "redelivered-" + mode, "acct-2", 1_000);

		service.process(event);
		service.process(event);   // what a redelivery after a crash looks like

		assertThat(rows(event.paymentId())).isEqualTo(expectedRows);
	}

	@ParameterizedTest
	@CsvSource({"event-id, 2", "payment-id, 1"})
	void aDoubleSubmitIsCaughtOnlyByThePaymentId(String key, long expectedRows) {
		var service = new PaymentService(db, "claim-first", key);
		String paymentId = "submitted-twice-" + key;

		service.process(new PaymentEvent("event-a-" + key, paymentId, "acct-3", 1_000));
		service.process(new PaymentEvent("event-b-" + key, paymentId, "acct-3", 1_000));   // the client pressed pay twice

		assertThat(rows(paymentId)).isEqualTo(expectedRows);
	}

	private long rows(String paymentId) {
		return db.sql("select count(*) from payments where payment_id = ?").param(paymentId).query(Long.class).single();
	}
}
