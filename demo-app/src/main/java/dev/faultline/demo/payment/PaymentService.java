package dev.faultline.demo.payment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentService {
    private final JdbcClient db;

    PaymentService(JdbcClient db) { this.db = db; }

    @Transactional
    public void process(PaymentRequested p) {
        db.sql("update accounts set balance_minor = balance_minor - ? where account_id = ?")
          .params(p.amountMinor(), p.accountId()).update();
        db.sql("insert into payments(payment_id, account_id, amount_minor) values (?, ?, ?)")
          .params(p.paymentId(), p.accountId(), p.amountMinor()).update();
    }
}
