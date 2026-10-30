package dev.faultline.demo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.faultline.demo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "demo.fixes.dedupe=true")
class DedupeFixTest {

	@Autowired
	PaymentService service;

	@Autowired
	JdbcClient db;

	@Test
	void redeliveredPaymentIsAppliedOnce() {
		var payment = new PaymentRequested("dedupe-1", "acct-2", 1_000);

		service.process(payment);
		service.process(payment);   // what a redelivery after a crash looks like

		assertThat(db.sql("select count(*) from payments where payment_id = 'dedupe-1'").query(Long.class).single()).isEqualTo(1);
		assertThat(db.sql("select balance_minor from accounts where account_id = 'acct-2'").query(Long.class).single()).isEqualTo(999_000L);
	}
}
