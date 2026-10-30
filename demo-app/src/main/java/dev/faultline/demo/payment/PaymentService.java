package dev.faultline.demo.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentService {
    private final JdbcClient db;
    private final boolean dedupe;

    PaymentService(JdbcClient db, @Value("${demo.fixes.dedupe:false}") boolean dedupe) {
        this.db = db;
        this.dedupe = dedupe;
    }

    @Transactional
    public void process(PaymentRequested p) {
        // B1 fix: the processed marker commits together with the debit, so a redelivered message is skipped
        if (dedupe && db.sql("insert into processed_events(payment_id) values (?) on conflict do nothing")
                .param(p.paymentId()).update() == 0) {
            return;
        }
        db.sql("update accounts set balance_minor = balance_minor - ? where account_id = ?")
          .params(p.amountMinor(), p.accountId()).update();
        db.sql("insert into payments(payment_id, account_id, amount_minor) values (?, ?, ?)")
          .params(p.paymentId(), p.accountId(), p.amountMinor()).update();
    }
}
